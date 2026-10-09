#!/usr/bin/env bash
#
# Edqiu 本机构建迭代器（缓存隔离仪式）
#
# 背景：本机安全代理会在 Gradle 守护进程退出后把 .lock 文件永久锁死（rename/delete 全拒），
# 造成「一个 GRADLE_USER_HOME 只能构建一次」。因此每次构建都用全新的 home：
#   1. 从最近一次 home_fresh* 复制依赖缓存与 wrapper 发行包（跳过 *.lock），保证离线可用
#   2. chmod +w 解除复制带来的只读属性
#   3. 换全新 ANDROID_USER_HOME（避免 debug.keystore.lock 被毒化）
#   4. 把项目 .gradle 与 app/build 改名让位（保留上一次产物便于对比）
#   5. 用项目自带 wrapper 执行 assembleDebug
#
# 用法：
#   bash tools/build_iter.sh <编号>
#   例：bash tools/build_iter.sh 7      → 使用 <工作区>/gradle/home_fresh7
#
# 换机器时用环境变量覆盖（都不设也能跑，默认值见下）：
#   EDQIU_BUILD_WORK  构建工作区，默认 D:/AndroidDev（缓存 home 与 android_user_home 都建在其下）
#   JAVA_HOME         JDK 21，默认 <工作区>/jdk/jdk21
#   ANDROID_HOME      Android SDK，默认 <工作区>/sdk
#   PYTHON            Python 3 解释器，默认按 python3 → python → py 顺序探测
#   EDQIU_TASKS       要执行的 Gradle 任务，默认 assembleDebug
#
set -uo pipefail

# 仓库根 = 本脚本所在目录（tools/）的上一级 —— 不写死绝对路径，挪仓库/换机器都不用改
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WORK="${EDQIU_BUILD_WORK:-D:/AndroidDev}"
JDK_HOME="${JAVA_HOME:-$WORK/jdk/jdk21}"
SDK_HOME="${ANDROID_HOME:-$WORK/sdk}"
TASKS="${EDQIU_TASKS:-assembleDebug}"

# 环境里可能残留失效的 JAVA_HOME / ANDROID_HOME（本机实测有 C:\temp\jdk21\... 这类已删除的路径），
# 直接用会让构建以「JAVA_HOME is set to an invalid directory」失败。这里校验后回退到默认值。
if [ ! -x "$JDK_HOME/bin/java" ] && [ ! -f "$JDK_HOME/bin/java.exe" ]; then
  echo "警告：JAVA_HOME=$JDK_HOME 不是有效的 JDK，回退到默认 $WORK/jdk/jdk21" >&2
  JDK_HOME="$WORK/jdk/jdk21"
fi
if [ ! -d "$SDK_HOME/platforms" ]; then
  echo "警告：ANDROID_HOME=$SDK_HOME 下没有 platforms/，回退到默认 $WORK/sdk" >&2
  SDK_HOME="$WORK/sdk"
fi
if [ ! -x "$JDK_HOME/bin/java" ] && [ ! -f "$JDK_HOME/bin/java.exe" ]; then
  echo "错误：未找到可用 JDK。请设置 JAVA_HOME，或用 EDQIU_BUILD_WORK 指定工作区。" >&2
  exit 1
fi

T="${1:-}"
if [ -z "$T" ]; then
  echo "用法: bash tools/build_iter.sh <编号>" >&2
  echo "  例: bash tools/build_iter.sh 7   → 使用 $WORK/gradle/home_fresh7" >&2
  exit 1
fi

# —— 定位 Python ——
# 缓存复制保留 Python 实现：缓存动辄数万文件，纯 shell 逐文件复制会慢一个量级。
if [ -z "${PYTHON:-}" ]; then
  for candidate in python3 python py; do
    if command -v "$candidate" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
  done
fi
if [ -z "${PYTHON:-}" ]; then
  echo "错误：未找到 Python 3。可用 PYTHON=<解释器路径> 指定。" >&2
  exit 1
fi

# Git Bash 下 pwd 给的是 /d/AndroidDev 这类路径，Windows 上的 Python 不认，需转成 D:/AndroidDev
win_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
BASE_W="$(win_path "$BASE")"
WORK_W="$(win_path "$WORK")"

DST_HOME="$WORK_W/gradle/home_fresh$T"
AUH_DIR="$WORK_W/android_user_home_$T"

export JAVA_HOME="$JDK_HOME"
export ANDROID_HOME="$SDK_HOME"
export ANDROID_SDK_ROOT="$SDK_HOME"

echo "==> 仓库根     : $BASE"
echo "==> 构建工作区 : $WORK"
echo "==> JDK / SDK  : $JDK_HOME / $SDK_HOME"
echo "==> 目标 home  : $DST_HOME"

# 环境变量传参，避免把 Windows 路径插值进 Python 源码（反斜杠/引号易错）
EDQIU_BASE="$BASE_W" EDQIU_WORK="$WORK_W" EDQIU_T="$T" EDQIU_DST="$DST_HOME" "$PYTHON" - <<'PY'
import glob, os, re, shutil, stat

work = os.environ["EDQIU_WORK"]
base = os.environ["EDQIU_BASE"]
t    = os.environ["EDQIU_T"]
dst  = os.environ["EDQIU_DST"]

# 缓存源：取编号最大的 home_fresh*（含上一次构建残留），让依赖零重复下载
best = None
for home in glob.glob(os.path.join(work, "gradle", "home_fresh*")):
    m = re.search(r"home_fresh(\d+)$", home)
    if m and (best is None or int(m.group(1)) > best[0]):
        best = (int(m.group(1)), home)
src = best[1] if best else os.path.join(work, "gradle", "home")
print(f"==> 缓存源     : {src}", flush=True)

# 依赖缓存 + wrapper 发行包（wrapper 一起带上，./gradlew 才能离线跑起来）
dst_caches = os.path.join(dst, "caches")
os.makedirs(dst_caches, exist_ok=True)
for item in ("modules-2", "transforms-3", "transforms-4", "jars-9"):
    s = os.path.join(src, "caches", item)
    d = os.path.join(dst_caches, item)
    if os.path.isdir(s) and not os.path.exists(d):
        print(f"    复制 caches/{item} ...", flush=True)
        shutil.copytree(s, d, ignore=shutil.ignore_patterns("*.lock"))

s = os.path.join(src, "wrapper")
d = os.path.join(dst, "wrapper")
if os.path.isdir(s) and not os.path.exists(d):
    # 只带项目 pin 的那一个 Gradle 发行包：wrapper/dists 下会堆积历次版本（十几 GB 级），
    # 全量复制纯属浪费。版本从 gradle-wrapper.properties 的 distributionUrl 解析。
    dist_name = None
    props = os.path.join(base, "gradle", "wrapper", "gradle-wrapper.properties")
    try:
        with open(props, encoding="utf-8") as fh:
            m = re.search(r"gradle-([\d.]+)-(?:bin|all)\.zip", fh.read())
            if m:
                dist_name = f"gradle-{m.group(1)}-bin"
    except OSError:
        pass

    if dist_name:
        os.makedirs(os.path.join(d, "dists"), exist_ok=True)
        picked = 0
        for entry in os.listdir(os.path.join(s, "dists")) if os.path.isdir(os.path.join(s, "dists")) else []:
            if entry.startswith(dist_name):
                print(f"    复制 wrapper/dists/{entry} ...", flush=True)
                shutil.copytree(
                    os.path.join(s, "dists", entry),
                    os.path.join(d, "dists", entry),
                    ignore=shutil.ignore_patterns("*.lock"),
                )
                picked += 1
        if picked == 0:
            print(f"    警告：{dist_name} 不在缓存源中，./gradlew 首次运行需联网下载", flush=True)
    else:
        print("    无法解析 distributionUrl，改为整体复制 wrapper", flush=True)
        shutil.copytree(s, d, ignore=shutil.ignore_patterns("*.lock"))

# 解除复制带来的只读属性（半毒化）
for root, dirs, files in os.walk(dst):
    for name in files + dirs:
        try:
            os.chmod(os.path.join(root, name), stat.S_IWRITE | stat.S_IREAD)
        except OSError:
            pass
print("    缓存就绪", flush=True)

# 一并带走 home 级配置（不只是缓存）：
#   init.d/proxy.init.gradle —— 动态识别代理（环境变量 → 系统代理 → 常见端口探测）。
#   它被刻意放在 init.d 而不是 gradle.properties，正是为了避免「代理关掉就构建失败」；
#   不复制它，新 home 就失去了代理自动识别，一旦有工件缺失会直接失败而非降级。
for item in ("init.d", "gradle.properties"):
    s = os.path.join(src, item)
    d = os.path.join(dst, item)
    if os.path.isdir(s) and not os.path.isdir(d):
        print(f"    复制 {item}/ ...", flush=True)
        shutil.copytree(s, d, ignore=shutil.ignore_patterns("*.lock"))
    elif os.path.isfile(s) and not os.path.exists(d):
        print(f"    复制 {item} ...", flush=True)
        shutil.copy2(s, d)

# 让位：项目 .gradle 与 app/build 改名，避开旧锁与增量状态
for name in (".gradle", "app/build"):
    p = os.path.join(base, name)
    if os.path.exists(p):
        os.rename(p, f"{p}_iter{t}")
        print(f"    让位 {name} -> {name}_iter{t}")

os.makedirs(os.path.join(work, f"android_user_home_{t}"), exist_ok=True)
print("    ANDROID_USER_HOME 就绪", flush=True)
PY
copy_code=$?
if [ "$copy_code" -ne 0 ]; then
  echo "错误：缓存准备失败（退出码 $copy_code）" >&2
  exit "$copy_code"
fi

echo "==> $TASKS（GRADLE_USER_HOME=$DST_HOME）"
cd "$BASE" || exit 1
ANDROID_USER_HOME="$AUH_DIR" \
GRADLE_USER_HOME="$DST_HOME" \
./gradlew --no-daemon $TASKS --console=plain 2>&1 | tail -60
code="${PIPESTATUS[0]}"

echo "==> EXIT_CODE=$code"
[ "$code" -eq 0 ] && echo "==> 产物：app/build/outputs/apk/debug/"
exit "$code"

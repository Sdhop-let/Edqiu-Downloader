#!/bin/bash
# XInvox 构建迭代器（套用 JiyiApp build_iter.sh 机制）
# 本机安全代理会把 Gradle 的 .lock 文件在守护进程退出后永久锁死（rename/delete 全拒），
# 导致"一个 GRADLE_USER_HOME 只能构建一次"。因此每次构建用全新 home：
#   1. 从源 home 复制依赖缓存（modules-2/transforms-3/transforms-4/jars-9），跳过 .lock
#   2. chmod +w（Windows 标志 S_IWRITE|S_IREAD），解除复制带来的只读/半毒化属性
#   3. 换全新 ANDROID_USER_HOME（避免 debug.keystore.lock 毒化）
#   4. rename 项目 .gradle 与 app/build 让位
#   5. assembleDebug
#
# 用法：bash build_iter.sh <to>       （源固定为 D:/AndroidDev/gradle/home）
# 例：  bash build_iter.sh 4          （缓存复制到 home_fresh4，构建在其上）

T="$1"
if [ -z "$T" ]; then echo "用法: bash build_iter.sh <to_home编号>"; exit 1; fi

PY="C:/Users/LENOVO/.workbuddy/binaries/python/versions/3.13.12/python.exe"
GRADLE="C:/Users/LENOVO/gradle-8.7-ea/gradle-8.7/bin/gradle.bat"
BASE="C:/Users/LENOVO/xinvox-local-build2"
# 缓存源：自动探测最新的 home_fresh*（含上一次构建/中断残留），保证依赖零重复下载
SRC_HOME="$("$PY" - <<PY
import glob, os, re
homes = glob.glob(r'D:/AndroidDev/gradle/home_fresh*')
nums = []
for h in homes:
    m = re.search(r'home_fresh(\d+)$', h)
    if m: nums.append((int(m.group(1)), h))
if nums:
    print(max(nums)[1])
else:
    print(r'D:/AndroidDev/gradle/home')
PY
)"
DST_HOME="D:/AndroidDev/gradle/home_fresh$T"
export JAVA_HOME="C:/temp/jdk21/jdk-21.0.6+7"
export ANDROID_SDK_ROOT="D:/AndroidDev/sdk"
export ANDROID_HOME="D:/AndroidDev/sdk"

echo "==> 复制依赖缓存: $SRC_HOME/caches -> $DST_HOME/caches"
"$PY" - <<PY
import shutil, os, stat
src = r'$SRC_HOME' + r'\caches'
dst = r'$DST_HOME' + r'\caches'
os.makedirs(dst, exist_ok=True)
for item in ['modules-2', 'transforms-3', 'transforms-4', 'jars-9']:
    s = os.path.join(src, item)
    d = os.path.join(dst, item)
    if os.path.isdir(s) and not os.path.exists(d):
        print('  copying', item, '...', flush=True)
        shutil.copytree(s, d, ignore=shutil.ignore_patterns('*.lock'))
        print('  done', item, flush=True)
for root, dirs, files in os.walk(dst):
    for f in files + dirs:
        try:
            os.chmod(os.path.join(root, f), stat.S_IWRITE | stat.S_IREAD)
        except OSError:
            pass
print('cache ready for home_fresh$T', flush=True)
PY

echo "==> 让位项目 .gradle / app/build + 换新 ANDROID_USER_HOME"
"$PY" - <<PY
import os
base = r'$BASE'
for name in ['.gradle', 'app/build']:
    s = os.path.join(base, name)
    if os.path.exists(s):
        os.rename(s, s + '_iter$T')
        print('  renamed', name)
os.makedirs(r'D:\AndroidDev\android_user_home_$T', exist_ok=True)
print('  fresh android user home ready')
PY

echo "==> assembleDebug (GRADLE_USER_HOME=$DST_HOME)"
cd "$BASE" || exit 1
ANDROID_USER_HOME="D:/AndroidDev/android_user_home_$T" \
GRADLE_USER_HOME="$DST_HOME" \
"$GRADLE" --no-daemon assembleDebug --console=plain 2>&1 | tail -60

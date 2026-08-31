#!/bin/bash
# Edqiu(repo) 构建脚本 — 套用本机 Gradle 锁毒化铁律
# 用法: bash build_push.sh
set -e

BASE="D:/Edqiu/repo"
PY="C:/Users/LENOVO/.workbuddy/binaries/python/versions/3.13.12/python.exe"
GRADLE="C:/Users/LENOVO/gradle-8.7-ea/gradle-8.7/bin/gradle.bat"
export JAVA_HOME="C:/temp/jdk21/jdk-21.0.6+7"
export ANDROID_SDK_ROOT="D:/AndroidDev/sdk"
export ANDROID_HOME="D:/AndroidDev/sdk"

# 1. 探测最新 home_fresh 作为缓存源
SRC_HOME="$("$PY" - <<PY
import glob, re
homes = glob.glob(r'D:/AndroidDev/gradle/home_fresh*')
nums = []
for h in homes:
    m = re.search(r'home_fresh(\d+)$', h)
    if m: nums.append(int(m.group(1)))
print('home_fresh' + str(max(nums)) if nums else r'D:/AndroidDev/gradle/home')
PY
)"
TS=$(date +%H%M%S)
DST_HOME="D:/AndroidDev/gradle/home_edqiu_${TS}"
echo "==> 缓存源: $SRC_HOME"

# 2. 复制依赖缓存（跳过 .lock）+ chmod
"$PY" - <<PY
import shutil, os, stat
src = r'$SRC_HOME' + r'\caches'
dst = r'$DST_HOME' + r'\caches'
os.makedirs(dst, exist_ok=True)
for item in ['modules-2', 'transforms-3', 'transforms-4', 'jars-9']:
    s = os.path.join(src, item)
    d = os.path.join(dst, item)
    if os.path.isdir(s) and not os.path.exists(d):
        print('  copying', item, flush=True)
        shutil.copytree(s, d, ignore=shutil.ignore_patterns('*.lock'))
for root, dirs, files in os.walk(dst):
    for f in files + dirs:
        try: os.chmod(os.path.join(root, f), stat.S_IWRITE | stat.S_IREAD)
        except OSError: pass
print('cache ready', flush=True)
PY

# 3. 让位项目 .gradle / app/build + 新 ANDROID_USER_HOME
"$PY" - <<PY
import os
base = r'$BASE'
for name in ['.gradle', 'app/build']:
    s = os.path.join(base, name)
    if os.path.exists(s):
        os.rename(s, s + '_prev$TS')
        print('  renamed', name, '->', name + '_prev$TS')
os.makedirs(r'D:\AndroidDev\android_user_home_$TS', exist_ok=True)
print('  fresh android user home ready')
PY

# 4. assembleDebug
echo "==> assembleDebug (GRADLE_USER_HOME=$DST_HOME)"
cd "$BASE" || exit 1
ANDROID_USER_HOME="D:/AndroidDev/android_user_home_$TS" \
GRADLE_USER_HOME="$DST_HOME" \
"$GRADLE" --no-daemon assembleDebug --console=plain 2>&1 | tail -80
echo "==> EXIT_CODE=${PIPESTATUS[0]}"

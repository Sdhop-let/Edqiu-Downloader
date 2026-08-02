#!/usr/bin/env bash
# ============================================================
# XInvox 版本备份脚本 — 每次升级小版本后执行，推送至 GitHub 备份仓库
# 仓库: https://github.com/qiuqiu-fist/Es-Qp.git (分支 main)
# 用法: bash backup.sh  [可选: 附加提交说明]
# ============================================================
set -e
cd "$(dirname "$0")"

# 1. 提取当前版本号
VNAME=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | head -1 | sed 's/versionName = "\(.*\)"/\1/')
VCODE=$(grep -o 'versionCode = [0-9]*' app/build.gradle.kts | head -1 | sed 's/versionCode = //')
echo "==> 当前版本: v${VNAME} (versionCode ${VCODE})"

# 2. 检查是否有未提交变更
if git status --porcelain | grep -q .; then
  echo "==> 检测到变更，开始提交..."
else
  echo "==> 无任何变更，无需备份"
  exit 0
fi

# 3. 暂存并提交
git add -A
EXTRA="$1"
if [ -n "$EXTRA" ]; then
  git commit -m "chore: v${VNAME} (versionCode ${VCODE}) 备份 — ${EXTRA}"
else
  git commit -m "chore: v${VNAME} (versionCode ${VCODE}) 备份"
fi

# 4. 推送
git push origin main
echo "==> 备份完成: v${VNAME} 已推送至 https://github.com/qiuqiu-fist/Es-Qp"

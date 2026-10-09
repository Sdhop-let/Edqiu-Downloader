#!/usr/bin/env bash
#
# Edqiu 构建（一次性）—— 缓存隔离仪式的入口封装。
#
# 原先本脚本自带一份与 build_iter.sh 近乎重复的实现，且产物 home 命名为 home_edqiu_*，
# 与 build_iter.sh 的 home_fresh* 探测规则不匹配，导致它写出的缓存永远无法被下次构建复用。
# 现直接委托给 build_iter.sh，用时间戳作为编号：既消除重复，产物也成为有效的缓存源。
#
# 用法：bash tools/build_push.sh
# 需要指定任务时：EDQIU_TASKS=assembleRelease bash tools/build_push.sh
# 其余可覆盖的环境变量见 tools/build_iter.sh 头部说明。
#
set -uo pipefail

exec bash "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build_iter.sh" "$(date +%H%M%S)"

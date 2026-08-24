#!/bin/sh
# 规范器 CI 门禁：对 PR 的 diff 运行 ftckb check，存在硬违规即失败。
# 用法：scripts/check-gate.sh <repo-root> <knowledge-root> <team> <season> [diff-file]
# 无 diff-file 时检查工作树相对 HEAD 的改动（本地提交前自检）。
set -u
ROOT=$1
KNOWLEDGE=$2
TEAM=$3
SEASON=$4
DIFF=${5:-}

FTCKB="$ROOT/apps/knowledge-cli/build/install/ftckb/bin/ftckb"
if [ ! -x "$FTCKB" ]; then
  echo "building ftckb..."
  (cd "$ROOT" && ./gradlew :apps:knowledge-cli:installDist --no-daemon) || exit 2
fi

if [ -n "$DIFF" ]; then
  "$FTCKB" check "$ROOT" --knowledge "$KNOWLEDGE" --team "$TEAM" --season "$SEASON" --diff "$DIFF" --json
else
  "$FTCKB" check "$ROOT" --knowledge "$KNOWLEDGE" --team "$TEAM" --season "$SEASON" --json
fi
exit $?

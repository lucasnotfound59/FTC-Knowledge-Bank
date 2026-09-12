#!/bin/sh
# 规范器 CI 门禁：对 PR 的 diff 运行 ftckb check，存在硬违规即失败。
# 用法：scripts/check-gate.sh REPO KNOWLEDGE TEAM SEASON PROFILE_OR_GENERIC [DIFF]
# 无 diff-file 时检查工作树相对 HEAD 的改动（本地提交前自检）。
set -u
if [ "$#" -ne 5 ] && [ "$#" -ne 6 ]; then
  echo "usage: check-gate.sh REPO KNOWLEDGE TEAM SEASON PROFILE_OR_GENERIC [DIFF]" >&2
  exit 64
fi
ROOT=$1
KNOWLEDGE=$2
TEAM=$3
SEASON=$4
PROFILE_OR_GENERIC=$5
ARG_COUNT=$#
DIFF=${6:-}

FTCKB="$ROOT/apps/knowledge-cli/build/install/ftckb/bin/ftckb"
if [ ! -x "$FTCKB" ]; then
  echo "building ftckb..."
  (cd "$ROOT" && ./gradlew :apps:knowledge-cli:installDist --no-daemon) || exit 2
fi

set -- check "$ROOT" --knowledge "$KNOWLEDGE" --team "$TEAM" --season "$SEASON"
if [ "$PROFILE_OR_GENERIC" = generic ]; then
  set -- "$@" --generic-profile
else
  set -- "$@" --profile "$PROFILE_OR_GENERIC"
fi
if [ "$ARG_COUNT" -eq 6 ]; then
  set -- "$@" --diff "$DIFF"
fi
"$FTCKB" "$@" --json
exit $?

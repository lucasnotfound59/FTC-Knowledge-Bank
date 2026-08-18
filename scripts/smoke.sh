#!/bin/sh
# One-click smoke check: build ftckb and verify the kernel contract end to end.
# Portable: works on any machine; JDK 21 is auto-provisioned by the build,
# and restricted environments can redirect the Gradle home (see README).
set -u
cd "$(dirname "$0")/.."
FTCKB="apps/knowledge-cli/build/install/ftckb/bin/ftckb"
PASS=0
FAIL=0
pass() { PASS=$((PASS+1)); echo "PASS $1"; }
fail() { FAIL=$((FAIL+1)); echo "FAIL $1"; }

# Restricted environments (sandboxes/CI) may forbid writing ~/.gradle; fall back
# to a temp Gradle home before building.
if [ -z "${GRADLE_USER_HOME:-}" ]; then
  if [ -w "$HOME/.gradle" ] || { mkdir -p "$HOME/.gradle" 2>/dev/null && [ -w "$HOME/.gradle" ]; }; then
    :
  else
    export GRADLE_USER_HOME="/tmp/ftckb-gradle"
    echo "note: ~/.gradle not writable; using GRADLE_USER_HOME=$GRADLE_USER_HOME"
  fi
fi

echo "== build (installDist) =="
if ./gradlew :apps:knowledge-cli:installDist --no-daemon >/tmp/ftckb-smoke-build.log 2>&1; then
  pass "build"
else
  fail "build"; tail -12 /tmp/ftckb-smoke-build.log; echo "smoke aborted: build failed"; exit 1
fi

if "$FTCKB" --help >/dev/null 2>&1; then pass "--help exits 0"; else fail "--help exits 0"; fi
if "$FTCKB" --version >/dev/null 2>&1; then pass "--version exits 0"; else fail "--version exits 0"; fi
if "$FTCKB" validate knowledge --json 2>/dev/null | grep -q '"ok":true'; then pass "validate knowledge --json"; else fail "validate knowledge --json"; fi
if "$FTCKB" resolve knowledge --team 20827 --season 2025-2026 --json 2>/dev/null | grep -q '"ok":true'; then pass "resolve 20827 --json"; else fail "resolve 20827 --json"; fi
if "$FTCKB" resolve knowledge --team 16093 --season 2025-2026 --json 2>/dev/null | grep -q '"ok":true'; then pass "resolve 16093 --json"; else fail "resolve 16093 --json"; fi
if "$FTCKB" resolve knowledge --season 2025-2026 --json >/dev/null 2>&1; then fail "resolve missing --team exits 64"; else pass "resolve missing --team exits nonzero"; fi
if "$FTCKB" validate no-such-knowledge-dir --json >/dev/null 2>&1; then fail "validate missing dir exits 2"; else pass "validate missing dir exits nonzero"; fi
if "$FTCKB" validate --help >/dev/null 2>&1; then pass "validate --help exits 0"; else fail "validate --help exits 0"; fi

echo "smoke: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]

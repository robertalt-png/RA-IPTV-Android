#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
cd "$scratch"
# Load the production wrapper without running emulator setup or installing APKs.
source <(sed '/^trap report_failure EXIT/,$d' "$repo/tools/qa-one-app.sh") phone
adb() {
  if [[ "$*" == *"am instrument"* ]]; then
    if [ "$mock_marker" = yes ]; then echo 'INSTRUMENTATION_RESULT: QA_RESULT=passed'; fi
    return "$mock_rc"
  fi
}
timeout() {
  if [[ "$1" == --kill-after=* ]]; then shift; fi
  shift
  "$@"
}
for scenario in passed missing timeout transport timeout-with-marker; do
  mock_marker=no
  mock_rc=0
  expected_rc=0
  case "$scenario" in
    passed) mock_marker=yes;;
    missing) expected_rc=2;;
    timeout) mock_rc=124; expected_rc=124;;
    transport) mock_rc=7; expected_rc=7;;
    timeout-with-marker) mock_marker=yes; mock_rc=124; expected_rc=124;;
  esac
  # Keep errexit active inside the subshell, as it is in the real QA script.
  set +e
  (set -e; run_instrument 1 "$scenario" QA_RESULT; touch "$scenario-continued") > "$scenario.log" 2>&1
  actual_rc=$?
  set -e
  if [ "$actual_rc" -ne "$expected_rc" ]; then
    cat "$scenario.log"
    echo "Wrong exit for $scenario: $actual_rc, expected $expected_rc" >&2
    exit 1
  fi
  if [ "$expected_rc" -ne 0 ]; then
    test ! -e "$scenario-continued"
    test -f "qa-results/$scenario-logcat.txt"
    grep -q '::error' "$scenario.log"
  fi
  if [ "$mock_rc" -eq 124 ]; then grep -q 'exceeded 1s' "$scenario.log"; fi
  echo "PASS $scenario"
done

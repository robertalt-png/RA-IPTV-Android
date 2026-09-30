#!/usr/bin/env bash
set -euo pipefail
EDITION="${1:?edition required}"
if [ "$EDITION" = "pro" ]; then
  DEFAULT_KS="$HOME/.android/debug.keystore"
  QA_KS="$HOME/.android/nenotv-qa-debug.keystore"
  if [ ! -f "$DEFAULT_KS" ]; then
    echo "Expected Gradle debug keystore was not created" >&2
    exit 2
  fi
  cp "$DEFAULT_KS" "$QA_KS"
fi
exec bash "$GITHUB_WORKSPACE/qa/scripts/run_emulator_qa.sh" "$@"

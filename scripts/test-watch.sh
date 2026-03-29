#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

SPEC="${1:-}"

require_cmd sbt
require_cmd verilator

(
  cd "${REPO_ROOT}"
  if [[ -z "${SPEC}" ]]; then
    sbt "~smokeTest"
  else
    sbt "~testOnly ${SPEC}"
  fi
)

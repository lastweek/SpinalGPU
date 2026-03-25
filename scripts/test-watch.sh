#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

SPEC="${1:-spinalgpu.GpuTopSimSpec}"

require_cmd sbt
require_cmd verilator

(
  cd "${REPO_ROOT}"
  sbt "~testOnly ${SPEC}"
)

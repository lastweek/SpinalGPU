#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

SPEC="${1:-spinalgpu.GpuTopSimSpec}"

require_cmd sbt
require_cmd verilator

"${SCRIPT_DIR}/build-kernels.sh"
run_sbt "testOnly ${SPEC}"

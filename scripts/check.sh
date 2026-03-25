#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_cmd sbt
require_cmd verilator

run_sbt compile
run_sbt test
run_sbt run

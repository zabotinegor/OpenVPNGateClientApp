#!/usr/bin/env bash
# Runs the provider-parity check and the unit tests of the shared CI scripts. Same command on both providers.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
python3 -m pip install --quiet pyyaml
python3 scripts/ci/check_pipeline_parity.py
python3 -m unittest discover -s scripts/ci/tests

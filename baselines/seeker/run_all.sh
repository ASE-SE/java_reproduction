#!/usr/bin/env bash
# End-to-end Seeker baseline run on the 1594-method dataset.
# Step 1: wrapper_seeker.py — drive Seeker over each sample, produce seeker_raw_output.json
# Step 2: refill.py         — fill in result* fields via java-scanner's MethodDiffAnalyzer
#
# Forwards any flags to wrapper_seeker.py (e.g. --limit 5 for smoke, --resume to continue).
# Refill always runs after wrapper finishes.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== step 1: wrapper_seeker.py $* ==="
python3 "$HERE/wrapper_seeker.py" "$@"

echo
echo "=== step 2: refill.py ==="
python3 "$HERE/refill.py"

echo
echo "all done."

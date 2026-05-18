#!/usr/bin/env bash
# Train task2 (catch-block generation, seq2seq via OpenNMT-py) for one config.
#
# Prereqs:
#   1. env 'nexgen' active
#   2. 03_prep_task2.py {nexgen|retrain|test} has produced under
#      $WORK_ROOT/<config>/baseline/:  src-{train,valid}.txt + tgt-{train,valid}.txt
#      (test config produces src-test.txt + tgt-test.txt)
#
# Pipeline (mirrors nexgen README task2):
#   a) `python prepare.py`  — builds multi_slicing variants from baseline txt
#   b) `sh preprocess.sh`   — OpenNMT preprocess -> binary data
#   c) `sh train.sh`        — OpenNMT train -> save_model_step_*.pt
#   d) `sh infer.sh`        — OpenNMT translate -> multi_slicing.out
#   e) perl multi-bleu + python evaluate.py -> BLEU + accuracy
#
# Usage:
#   ./06_train_task2.sh nexgen  /path/to/work_root  [GPU_ID]
#   ./06_train_task2.sh retrain /path/to/work_root  [GPU_ID]

set -euo pipefail

CONFIG="${1:?config: nexgen | retrain}"
WORK_ROOT="${2:?work_root path}"
GPU_ID="${3:-0}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEXGEN_TASK2="$HERE/../source/task2"

CONFIG_DIR="$WORK_ROOT/$CONFIG"
BASELINE_DIR="$CONFIG_DIR/baseline"
TASK2_DATA_DIR="$NEXGEN_TASK2/data"
TASK2_TESTOUT_DIR="$NEXGEN_TASK2/testout"

if [[ ! -f "$BASELINE_DIR/src-train.txt" ]]; then
    echo "ERROR: $BASELINE_DIR/src-train.txt missing. Run 03_prep_task2.py first." >&2
    exit 1
fi

# Make nexgen's task2/data point at this config's baseline files.
# Also copy test (if present) — falls back to using 1594 test from WORK_ROOT/test.
mkdir -p "$TASK2_DATA_DIR/baseline"
cp -f "$BASELINE_DIR"/src-train.txt    "$TASK2_DATA_DIR/baseline/src-train.txt"
cp -f "$BASELINE_DIR"/tgt-train.txt    "$TASK2_DATA_DIR/baseline/tgt-train.txt"
cp -f "$BASELINE_DIR"/src-valid.txt    "$TASK2_DATA_DIR/baseline/src-valid.txt"
cp -f "$BASELINE_DIR"/tgt-valid.txt    "$TASK2_DATA_DIR/baseline/tgt-valid.txt"
# Test set always comes from $WORK_ROOT/test/baseline/
TEST_BASELINE="$WORK_ROOT/test/baseline"
if [[ -f "$TEST_BASELINE/src-test.txt" ]]; then
    cp -f "$TEST_BASELINE"/src-test.txt "$TASK2_DATA_DIR/baseline/src-test.txt"
    cp -f "$TEST_BASELINE"/tgt-test.txt "$TASK2_DATA_DIR/baseline/tgt-test.txt"
else
    echo "WARN: $TEST_BASELINE/src-test.txt missing; task2 test stage will fail." >&2
fi

export CUDA_VISIBLE_DEVICES="$GPU_ID"

cd "$NEXGEN_TASK2"

echo "=== step a: prepare.py (multi_slicing) ==="
PYTHONPATH=".:$PYTHONPATH" python prepare.py

echo "=== step b: preprocess.sh (OpenNMT) ==="
PYTHONPATH=".:$PYTHONPATH" bash preprocess.sh

echo "=== step c: train.sh ==="
# Track best epoch by valid BLEU. nexgen's train.sh just runs N steps and
# saves checkpoints periodically. We pipe to a log so the orchestrator can
# parse and pick best.
LOG="$CONFIG_DIR/task2_train.log"
PYTHONPATH=".:$PYTHONPATH" bash train.sh 2>&1 | tee "$LOG"

# Find checkpoint with lowest valid perplexity (a proxy for best epoch).
# OpenNMT prints lines like:  Validation perplexity: 12.34
BEST_PPL_LINE="$(grep -n 'Validation perplexity' "$LOG" | sort -t: -k3 -n | head -1 || true)"
echo "best validation line: $BEST_PPL_LINE"

echo "=== step d: infer.sh ==="
PYTHONPATH=".:$PYTHONPATH" bash infer.sh 2>&1 | tee "$CONFIG_DIR/task2_infer.log"

# Stash the generated catch blocks for assembly.
OUT_FILE="$TASK2_TESTOUT_DIR/multi_slicing.out"
if [[ -f "$OUT_FILE" ]]; then
    cp "$OUT_FILE" "$CONFIG_DIR/task2_pred.txt"
    echo "task2 predictions: $CONFIG_DIR/task2_pred.txt"
else
    echo "WARN: infer.sh did not produce $OUT_FILE" >&2
fi

echo "DONE: task2 $CONFIG"

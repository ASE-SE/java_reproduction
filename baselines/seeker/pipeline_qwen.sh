#!/usr/bin/env bash
# 端到端等 seeker-qwen 跑完 → refill → 构建 detector / ranker 输出 → 重生成 compare.md
set -euo pipefail

PY=/home/lichenxu/miniconda3/envs/taffy/bin/python
SEEKER_DIR=/home/lichenxu/java_exception/java_exception/baselines/seeker
PROJECT_ROOT=/home/lichenxu/java_exception/java_exception
LOG="$SEEKER_DIR/seeker_qwen-max_with_detector_run.log"
RAW="$SEEKER_DIR/seeker_qwen-max_with_detector_raw.json"
AST_OUT="$SEEKER_DIR/seeker_qwen-max_output.json"
DET_OUT="$SEEKER_DIR/seeker_qwen_detector_output.json"
RANK_OUT="$SEEKER_DIR/seeker_qwen_ranker_output.json"

echo "[$(date +%T)] waiting for seeker-qwen full run..."
until grep -qE '^done\. wrote ' "$LOG" 2>/dev/null; do
    sleep 60
done
echo "[$(date +%T)] seeker run done. tail of log:"
tail -3 "$LOG"

echo "[$(date +%T)] step 1: refill (java-scanner AST) → $AST_OUT"
cd "$SEEKER_DIR"
export JAVA_HOME=/home/lichenxu/tools/jdk-17.0.18+8
export PATH="$JAVA_HOME/bin:$PATH"
"$PY" refill.py "$RAW" "$AST_OUT" 2>&1 | tail -3

echo "[$(date +%T)] step 2a: build seeker-qwen-detector_output.json"
"$PY" build_seeker_detector_output.py \
    --with-detector "$RAW" \
    --ast-output    "$AST_OUT" \
    --out           "$DET_OUT" \
    --signal detector

echo "[$(date +%T)] step 2b: build seeker-qwen-ranker_output.json (types = ranker top-1)"
"$PY" build_seeker_detector_output.py \
    --with-detector "$RAW" \
    --ast-output    "$AST_OUT" \
    --out           "$RANK_OUT" \
    --signal ranker

echo "[$(date +%T)] step 3: add 3 qwen entries to compare_methods.py (if not present) + regen md"
"$PY" - <<'PYINJECT'
from pathlib import Path
cm = Path("/home/lichenxu/java_exception/java_exception/data/compare_methods.py")
text = cm.read_text()
addition = (
    '    "seeker-qwen":          "baselines/seeker/seeker_qwen-max_output.json",\n'
    '    "seeker-qwen-detector": "baselines/seeker/seeker_qwen_detector_output.json",\n'
    '    "seeker-qwen-ranker":   "baselines/seeker/seeker_qwen_ranker_output.json",\n'
)
if '"seeker-qwen":' not in text:
    needle = '    "seeker-ranker":   "baselines/seeker/seeker_ranker_output.json",\n'
    text = text.replace(needle, needle + addition)
    cm.write_text(text)
    print("compare_methods.py: added seeker-qwen + seeker-qwen-detector + seeker-qwen-ranker")
else:
    print("compare_methods.py: seeker-qwen entries already present, no change")
PYINJECT

cd "$PROJECT_ROOT"
"$PY" data/compare_methods.py --md data/compare.md 2>&1 | tail -60

echo "[$(date +%T)] FULL_PIPELINE_DONE"

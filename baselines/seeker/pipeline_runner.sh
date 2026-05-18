#!/usr/bin/env bash
# 端到端等 seeker 跑完 → 自动构建 detector 融合输出 → 重生成 compare.md
set -euo pipefail

PY=/home/lichenxu/miniconda3/envs/taffy/bin/python
SEEKER_DIR=/home/lichenxu/java_exception/java_exception/baselines/seeker
PROJECT_ROOT=/home/lichenxu/java_exception/java_exception
LOG="$SEEKER_DIR/seeker_with_detector_run.log"

echo "[$(date +%T)] waiting for seeker full run to finish..."
until grep -qE '^done\. wrote ' "$LOG" 2>/dev/null; do
    sleep 60
done
echo "[$(date +%T)] seeker run done. tail of log:"
tail -3 "$LOG"

echo "[$(date +%T)] step 1: build seeker_detector_output.json"
cd "$SEEKER_DIR"
"$PY" build_seeker_detector_output.py \
    --with-detector seeker_deepseek-v4-flash_with_detector_raw.json \
    --ast-output    seeker_deepseek-v4-flash_output.json \
    --out           seeker_detector_output.json

echo "[$(date +%T)] step 2: regen compare.md"
cd "$PROJECT_ROOT"
"$PY" data/compare_methods.py --md data/compare.md | tail -50

echo "[$(date +%T)] FULL_PIPELINE_DONE"

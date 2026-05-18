#!/usr/bin/env bash
# Train task1 (try-block localization) for one configuration (nexgen or retrain).
#
# Prereqs (run from the GPU server, with env 'nexgen' active):
#   1. 04_setup_env.sh has installed the env
#   2. 02_prep_task1.py {nexgen|retrain|test} has produced train.pkl/val.pkl
#      under <work_root>/<config>/raw_pkl/
#   3. patches/train_task1.py + patches/utils_task1.py are in place
#      (orchestrator script 10_train_all.sh handles step 3)
#
# What this script does:
#   a) builds vocab.pt + TRAIN_data.pth.tar + VAL_data.pth.tar + TEST_data.pth.tar
#      via nexgen's utils.create_input_files() (test reuses 1594 pkl)
#   b) runs train mode for $EPOCHS epochs, saving checkpoint_N.pth.tar each
#   c) at the end, train_task1.py writes summary.json with best_epoch
#   d) runs predict_dump on best_epoch to produce per-method predictions JSON
#
# Usage:
#   ./05_train_task1.sh nexgen   /path/to/work_root  [GPU_ID]
#   ./05_train_task1.sh retrain  /path/to/work_root  [GPU_ID]
#
# work_root must already contain:
#   nexgen/raw_pkl/{train,val}.pkl     (from 02_prep_task1.py nexgen ...)
#   retrain/raw_pkl/{train,val}.pkl    (from 02_prep_task1.py retrain ...)
#   test/raw_pkl/test.pkl              (from 02_prep_task1.py test ...)
#
# After this script's run, the artefacts will be at:
#   work_root/<config>/output/             (encoded data + vocab)
#   work_root/<config>/checkpoints/        (per-epoch .pth.tar)
#   work_root/<config>/summary.json        (best epoch + val acc history)
#   work_root/<config>/per_line_pred.json  (test-set per-method predictions)

set -euo pipefail

CONFIG="${1:?config: nexgen | retrain}"
WORK_ROOT="${2:?work_root path}"
GPU_ID="${3:-0}"

if [[ "$CONFIG" != "nexgen" && "$CONFIG" != "retrain" ]]; then
    echo "ERROR: config must be 'nexgen' or 'retrain', got '$CONFIG'" >&2
    exit 1
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEXGEN_TASK1="$HERE/../source/task1"

CONFIG_DIR="$WORK_ROOT/$CONFIG"
RAW_PKL="$CONFIG_DIR/raw_pkl"
OUT_DIR="$CONFIG_DIR/output"
CKPT_DIR="$CONFIG_DIR/checkpoints"
SUMMARY="$CONFIG_DIR/summary.json"
PERLINE_JSON="$CONFIG_DIR/per_line_pred.json"
TEST_PKL_DIR="$WORK_ROOT/test/raw_pkl"

mkdir -p "$OUT_DIR" "$CKPT_DIR"

if [[ ! -f "$RAW_PKL/train.pkl" ]]; then
    echo "ERROR: $RAW_PKL/train.pkl missing. Run 02_prep_task1.py first." >&2
    exit 1
fi
if [[ ! -f "$TEST_PKL_DIR/test.pkl" ]]; then
    echo "ERROR: $TEST_PKL_DIR/test.pkl missing. Run 02_prep_task1.py test first." >&2
    exit 1
fi

# Assemble nexgen-style pkl folder: train.pkl, val.pkl, test.pkl all in one dir
# so create_input_files() can read them.
PKL_STAGING="$CONFIG_DIR/_pkl_staging"
mkdir -p "$PKL_STAGING"
cp -f "$RAW_PKL/train.pkl"          "$PKL_STAGING/train.pkl"
cp -f "$RAW_PKL/val.pkl"            "$PKL_STAGING/val.pkl"
cp -f "$TEST_PKL_DIR/test.pkl"      "$PKL_STAGING/test.pkl"

echo "=== step a: encoding pkl -> TRAIN/VAL/TEST_data.pth.tar via nexgen utils ==="
cd "$NEXGEN_TASK1"
PYTHONHASHSEED=0 python - <<PYEOF
import sys
sys.path.insert(0, "$NEXGEN_TASK1")
from utils import create_input_files
# nexgen's create_input_files writes TRAIN_data.pth.tar + TEST_data.pth.tar
# (we extend to handle VAL by calling read_pkl + saving manually).
create_input_files(
    pkl_folder="$PKL_STAGING",
    output_folder="$OUT_DIR",
    line_limit=50,
    word_limit=40,
    min_word_count=5,
    vocab_size=50000,
)
# Now also encode the VAL split with the same vocab.
import torch, os
from utils import read_pkl
vocab = torch.load(os.path.join("$OUT_DIR", "vocab.pt"))
PAD = vocab.stoi["<pad>"]
UNK = vocab.stoi["<unk>"]
val_docs, val_labels, _ = read_pkl("$PKL_STAGING", "val", 50, 40)
tmaps = {"0": 0, "1": 1, "<pad>": 2, "<start>": 3, "<end>": 4}
encoded_val = list(map(lambda doc: list(
    map(lambda s: list(map(lambda w: vocab.stoi.get(w, UNK), s)) +
        [PAD] * (40 - len(s)), doc)) +
        [[PAD] * 40] * (50 - len(doc)), val_docs))
lines_per_val_doc = list(map(lambda doc: len(doc), val_docs))
words_per_val_line = list(map(lambda doc: list(map(lambda s: len(s), doc)) + [0]*(50 - len(doc)), val_docs))
val_labels_padded = list(map(lambda y: y + [tmaps["<pad>"]]*(50 - len(y)), val_labels))
torch.save({"docs": encoded_val, "labels": val_labels_padded,
            "lines_per_document": lines_per_val_doc,
            "words_per_line": words_per_val_line},
           os.path.join("$OUT_DIR", "VAL_data.pth.tar"))
print("encoded VAL split:", len(encoded_val), "docs")
PYEOF

echo "=== step b: training ==="
export CUDA_VISIBLE_DEVICES="$GPU_ID"
export DATA_FOLDER="$OUT_DIR"
export CKPT_FOLDER="$CKPT_DIR"
export SUMMARY_PATH="$SUMMARY"
export EPOCHS="${EPOCHS:-20}"
export BATCH_SIZE="${BATCH_SIZE:-64}"

cd "$NEXGEN_TASK1"
# Use the patched train.py (placed by 10_train_all.sh, or via PYTHONPATH below).
PYTHONPATH="$HERE/../patches:$PYTHONPATH" python "$HERE/../patches/train_task1.py" train

BEST_EPOCH="$(python -c 'import json; print(json.load(open("'$SUMMARY'"))["best_epoch"])')"
echo "best_epoch = $BEST_EPOCH"

echo "=== step c: predict_dump on best epoch ==="
PYTHONPATH="$HERE/../patches:$PYTHONPATH" python "$HERE/../patches/train_task1.py" predict_dump "$BEST_EPOCH" "$PERLINE_JSON"

echo "DONE: $CONFIG"
echo "  summary:        $SUMMARY"
echo "  per_line_pred:  $PERLINE_JSON"

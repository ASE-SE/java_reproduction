#!/usr/bin/env bash
# Extract raw_data.tar.gz (=> exp_data.csv) from the cloned nexgen source.
# Idempotent: skips if exp_data.csv already exists.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$HERE/../source"

if [[ -f "$SOURCE_DIR/exp_data.csv" ]]; then
    echo "exp_data.csv already extracted at $SOURCE_DIR/exp_data.csv"
    exit 0
fi

if [[ ! -f "$SOURCE_DIR/raw_data.tar.gz" ]]; then
    echo "ERROR: raw_data.tar.gz not found at $SOURCE_DIR/raw_data.tar.gz" >&2
    exit 1
fi

echo "extracting raw_data.tar.gz (~102 MB compressed, ~348 MB extracted)..."
tar -xzf "$SOURCE_DIR/raw_data.tar.gz" -C "$SOURCE_DIR/"
echo "done: $SOURCE_DIR/exp_data.csv"
ls -lh "$SOURCE_DIR/exp_data.csv"

#!/usr/bin/env bash
# Create a conda env for nexgen reproduction on Ampere/Ada/Hopper GPU
# (RTX 30xx / 40xx / A100 / H100). Uses PyTorch 1.8 + CUDA 11.1, which is
# the lowest stable combo that has Ampere kernels. torchtext 0.9 keeps the
# legacy Vocab API that nexgen's utils.py expects, so no code changes.
#
# Why not Python 3.6 + torch 1.3.1 as nexgen's README says: torch 1.3.1
# wheels only ship CUDA kernels up to sm_75 (Turing). RTX 3090 = sm_86 will
# fail at runtime with "no kernel image is available". We bump runtime, not
# algorithm.
#
# Usage:
#   ./04_setup_env.sh                # creates conda env "nexgen"
#   ./04_setup_env.sh other-env-name # custom env name
set -euo pipefail

ENV_NAME="${1:-nexgen}"

if ! command -v conda >/dev/null 2>&1; then
    echo "ERROR: conda not found. Install miniconda first." >&2
    exit 1
fi

# shellcheck disable=SC1091
source "$(conda info --base)/etc/profile.d/conda.sh"

if conda env list | awk '{print $1}' | grep -qx "$ENV_NAME"; then
    echo "env '$ENV_NAME' already exists. Activate with:  conda activate $ENV_NAME"
    exit 0
fi

echo "creating conda env '$ENV_NAME' (Python 3.7)..."
conda create -y -n "$ENV_NAME" python=3.7
conda activate "$ENV_NAME"

echo "installing PyTorch 1.8.0 + torchtext 0.9.0 + CUDA 11.1 (Ampere-capable)..."
pip install \
    torch==1.8.0+cu111 \
    torchvision==0.9.0+cu111 \
    torchtext==0.9.0 \
    -f https://download.pytorch.org/whl/torch_stable.html

echo "installing nexgen Python deps (matching paper versions where possible)..."
pip install \
    pandas==1.3.5 \
    tqdm==4.62.3 \
    scikit-learn==1.0.2 \
    javalang==0.13.0 \
    numpy==1.21.6

echo "installing OpenNMT-py (for task2 seq2seq). The vendored task2/onmt/ in"
echo "the nexgen repo also works, but its OpenNMT is the very old fork; we"
echo "fall back to it via PYTHONPATH at run time. Skipping pip install here."

echo
echo "verifying installation..."
python - <<'PY'
import torch, torchtext, pandas, sklearn, javalang
print("torch       ", torch.__version__, "CUDA?", torch.cuda.is_available(), "device_count", torch.cuda.device_count())
print("torchtext   ", torchtext.__version__)
print("pandas      ", pandas.__version__)
print("sklearn     ", sklearn.__version__)
print("javalang    ", javalang.__version__ if hasattr(javalang, '__version__') else 'unknown')
# torchtext legacy Vocab API used by nexgen
from torchtext.vocab import Vocab
print("torchtext.vocab.Vocab importable:", Vocab is not None)
PY

echo
echo "DONE. Activate the env with:"
echo "  conda activate $ENV_NAME"

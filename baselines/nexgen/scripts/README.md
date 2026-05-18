# Nexgen baseline reproduction

Two trained models per task = **4 trainings total**:

| Tag                | Task   | Train data                                  | Picked epoch |
| ------------------ | ------ | ------------------------------------------- | ------------ |
| **Nexgen**         | task1  | `exp_data.csv` (nexgen, ~507K, 50/50)       | by val      |
| **Nexgen-retrain** | task1  | `exception_addition_commits_deduplicated.json` minus 1594 (~88K) | by val |
| **Nexgen**         | task2  | exp_data.csv (filtered to methods with try-catch) | by val |
| **Nexgen-retrain** | task2  | raw_mining minus 1594 (all positives, ~88K) | by val |

Test set for **all four** = `data/1594_input_data.json` (full 1594). Output JSONs end up with the same schema as `data/deepseek-v4-flash_output.json`, `baselines/seeker/seeker_*_output.json`, `baselines/Neurex/neurex_output.json`, so the `result_manager.ipynb` notebook treats them identically.

## File layout

```
baselines/nexgen/
├── source/                       # cloned from github.com/zhangj111/nexgen (don't edit)
│   ├── raw_data.tar.gz           # 102 MB compressed, 348 MB exp_data.csv
│   ├── task1/                    # nexgen task1 (HAN-LSTM)
│   ├── task2/                    # nexgen task2 (seq2seq via OpenNMT)
│   ├── task1data.py / task2data.py
│   └── ...
├── patches/
│   └── train_task1.py            # replaces nexgen task1/train.py: adds val + best-epoch
├── scripts/
│   ├── 01_extract_nexgen_raw.sh
│   ├── 02_prep_task1.py
│   ├── 03_prep_task2.py
│   ├── 04_setup_env.sh
│   ├── 05_train_task1.sh
│   ├── 06_train_task2.sh
│   ├── 07_assemble_method_result.py
│   ├── 08_refill.py
│   └── README.md (this file)
└── (legacy text files — old server access notes, kept for historical reference)
```

## End-to-end workflow

Steps **L** happen locally (this VM, CPU); **R** happen on the GPU server.

### L1. Sanity test data converters locally (optional but recommended)

```bash
# you should already be inside the project root
cd /home/lichenxu/java_exception/java_exception/baselines/nexgen

# extract exp_data.csv (~30 sec)
./scripts/01_extract_nexgen_raw.sh

# Generate the 1594 test pkl (only this one is small/fast)
python scripts/02_prep_task1.py test \
    ../../data/1594_input_data.json \
    work/test/raw_pkl

python scripts/03_prep_task2.py test \
    ../../data/1594_input_data.json \
    work/test

# Now you have:
#   work/test/raw_pkl/test.pkl              # 1594 task1 samples
#   work/test/baseline/src-test.txt + tgt-test.txt   # 552 task2 samples
```

The big prep jobs (nexgen 507K, retrain 88K) are slow on CPU (30–60 min each) — better run them on the GPU server where they'll also be needed.

### L2. Build transfer bundle

```bash
cd /home/lichenxu/java_exception/java_exception
tar -czf /tmp/nexgen_bundle.tar.gz \
    baselines/nexgen/source/ \
    baselines/nexgen/patches/ \
    baselines/nexgen/scripts/ \
    data/1594_input_data.json \
    data/exception_addition_commits_deduplicated.json \
    java-scanner/  # need it later for refill
ls -lh /tmp/nexgen_bundle.tar.gz   # ~500 MB
```

### L3. scp to GPU server

```bash
scp /tmp/nexgen_bundle.tar.gz jiangyanjie@<server>:/path/to/workspace/
```

---

### R1. On the GPU server: extract + setup env

```bash
cd /path/to/workspace/
tar -xzf nexgen_bundle.tar.gz
cd baselines/nexgen
./scripts/04_setup_env.sh     # creates conda env "nexgen", installs PyTorch 1.8 + CUDA 11.1
conda activate nexgen
```

### R2. Extract nexgen raw data + prep all 3 task1 splits

```bash
# extract exp_data.csv (348 MB) from raw_data.tar.gz
./scripts/01_extract_nexgen_raw.sh

# prep task1: produces train.pkl / val.pkl / test.pkl
WORK=$(pwd)/work
mkdir -p $WORK

python scripts/02_prep_task1.py nexgen \
    source/exp_data.csv \
    $WORK/nexgen/raw_pkl                # ~30 min

python scripts/02_prep_task1.py retrain \
    ../../data/exception_addition_commits_deduplicated.json \
    ../../data/1594_input_data.json \
    $WORK/retrain/raw_pkl               # ~5 min

python scripts/02_prep_task1.py test \
    ../../data/1594_input_data.json \
    $WORK/test/raw_pkl                  # < 1 min
```

### R3. Train task1 (2× trainings, single GPU each)

Each takes ~5h (retrain) to ~20h (nexgen full) on a 3090. Run sequentially or two in parallel on different cards:

```bash
# Pick a less-busy GPU. Per your nvidia-smi, card 0 / 4 are at lowest util.
./scripts/05_train_task1.sh nexgen  $WORK 0    # ~20h on GPU 0
./scripts/05_train_task1.sh retrain $WORK 0    # ~5h on GPU 0 (after nexgen done)
# or in parallel:
# ./scripts/05_train_task1.sh nexgen  $WORK 0 &
# ./scripts/05_train_task1.sh retrain $WORK 4 &
# wait
```

What's produced for each config:
- `$WORK/<config>/output/`         — encoded data + vocab
- `$WORK/<config>/checkpoints/`    — per-epoch .pth.tar
- `$WORK/<config>/summary.json`    — best_epoch + val acc history
- `$WORK/<config>/per_line_pred.json` — per-method per-line preds on the 1594 test set

### R4. Prep + train task2

```bash
python scripts/03_prep_task2.py nexgen \
    source/exp_data.csv \
    $WORK/nexgen                        # ~30-60 min (javalang tokenization)

python scripts/03_prep_task2.py retrain \
    ../../data/exception_addition_commits_deduplicated.json \
    ../../data/1594_input_data.json \
    $WORK/retrain                       # ~10 min

# task2 test set (552 samples, only label=1)
python scripts/03_prep_task2.py test \
    ../../data/1594_input_data.json \
    $WORK/test

./scripts/06_train_task2.sh nexgen  $WORK 0   # ~20-40h (seq2seq is heavier)
./scripts/06_train_task2.sh retrain $WORK 0   # ~5-10h
```

What's produced:
- `$WORK/<config>/task2_pred.txt` — generated catch block per test sample

### R5. Assemble methodResult JSON (task1 spans + task2 catch -> wrapped code)

```bash
python scripts/07_assemble_method_result.py \
    --input_1594 ../../data/1594_input_data.json \
    --task1_pred $WORK/nexgen/per_line_pred.json \
    --task2_pred $WORK/nexgen/task2_pred.txt \
    --out $WORK/nexgen_raw_output.json \
    --config_label nexgen

python scripts/07_assemble_method_result.py \
    --input_1594 ../../data/1594_input_data.json \
    --task1_pred $WORK/retrain/per_line_pred.json \
    --task2_pred $WORK/retrain/task2_pred.txt \
    --out $WORK/retrain_raw_output.json \
    --config_label retrain
```

### R6. tarball results back to local

```bash
tar -czf /tmp/nexgen_outputs.tar.gz \
    $WORK/nexgen_raw_output.json \
    $WORK/retrain_raw_output.json \
    $WORK/nexgen/per_line_pred.json \
    $WORK/retrain/per_line_pred.json \
    $WORK/nexgen/summary.json \
    $WORK/retrain/summary.json \
    $WORK/nexgen/task2_pred.txt \
    $WORK/retrain/task2_pred.txt
```

scp back to local.

---

### L4. Refill via java-scanner (back on local)

```bash
cd /home/lichenxu/java_exception/java_exception/baselines/nexgen
# move incoming tarball into place...
tar -xzf /tmp/nexgen_outputs.tar.gz -C ./

# refill: writes the result*StartLine/EndLine/NoNestingLines fields via AST diff
python scripts/08_refill.py \
    nexgen_raw_output.json  nexgen_output.json
python scripts/08_refill.py \
    retrain_raw_output.json retrain_output.json
```

Now you have `nexgen_output.json` and `retrain_output.json` with the same schema as `data/deepseek-v4-flash_output.json` and `baselines/seeker/seeker_deepseek-v4-flash_output.json`.

### L5. Compute metrics

In `data/result_manager.ipynb`, change `output_file` to point at each in turn and rerun the cells:

```python
output_file = "baselines/nexgen/nexgen_output.json"
# -> Nexgen detection precision/recall/F1, line-level metrics, etc.

output_file = "baselines/nexgen/retrain_output.json"
# -> Nexgen-retrain metrics
```

Compare to `data/deepseek-v4-flash_output.json` (main), `baselines/seeker/seeker_deepseek-v4-flash_output.json` (Seeker), `baselines/Neurex/neurex_output.json` (Neurex).

## Caveats / known limitations

1. **Retrain training data is all-positive at method level.** Our raw mining (`exception_addition_commits_deduplicated.json`) contains only methods that had try-catch added — no "fully-negative" methods. The per-line classifier still gets both labels (lines inside try = 1, lines outside = 0 within the same positive method), but no methods with all-zero labels are in training. As a result, Nexgen-retrain may over-predict positives on label=0 test samples. The paper text records this.

2. **Val/test boundary.** Per-epoch validation uses a 10% holdout of the training data (independent of 1594 test). No val/test leakage. This differs from nexgen's original `train.py` which used test as val.

3. **PyTorch runtime upgrade.** Nexgen specifies PyTorch 1.3.1, but Ampere (3090) requires PyTorch 1.8+. We use 1.8.0 with CUDA 11.1. Algorithm, model, and hyperparameters are unchanged — only the framework runtime version is bumped. `torchtext==0.9.0` is used for legacy `torchtext.vocab.Vocab` API compatibility with nexgen's `utils.py`.

4. **OpenNMT vendored.** Task2 uses the OpenNMT-py fork vendored under `source/task2/onmt/`. We do not install a separate OpenNMT-py because that fork has nexgen-specific tweaks.

5. **Single GPU per training.** Two trainings can run in parallel on different GPUs (different `CUDA_VISIBLE_DEVICES`), but each individual training is single-card. Multi-card DataParallel was not enabled to keep code changes minimal.

6. **Server is shared** (your `nvidia-smi` shows 8× 3090 in use by jiangyanjie/modigen). Pick a card with low utilization and plenty of free VRAM (nexgen model only needs ~2 GB).

## Quick reference

| Stage           | Script                      | Where  | Approx time on 3090 |
| --------------- | --------------------------- | ------ | ------------------- |
| Env setup       | `04_setup_env.sh`           | server | 5 min               |
| Prep task1 nexgen | `02_prep_task1.py nexgen` | server | 30 min              |
| Prep task1 retrain | `02_prep_task1.py retrain` | server | 5 min               |
| Train task1 nexgen | `05_train_task1.sh nexgen` | server | ~20 h               |
| Train task1 retrain | `05_train_task1.sh retrain` | server | ~5 h               |
| Prep task2 nexgen | `03_prep_task2.py nexgen` | server | 30-60 min           |
| Train task2 nexgen | `06_train_task2.sh nexgen` | server | ~20-40 h            |
| Train task2 retrain | `06_train_task2.sh retrain` | server | ~5-10 h           |
| Assemble + refill | `07` + `08`               | mixed  | a few minutes       |

Total wall time single-GPU: ~50-80 hours of GPU time spread over several days.

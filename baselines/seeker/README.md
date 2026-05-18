# Seeker baseline runner

Drives [Seeker](https://github.com/sssszh/Seeker) (multi-agent exception-handling
framework, arXiv 2412.11713) as a **black box** over our 1594-method sample set,
then refills the `result*` fields using the main pipeline's `MethodDiffAnalyzer`
so the output JSON is directly comparable with the main method's output.

## Layout

```
baselines/seeker/
├── wrapper_seeker.py            # step 1: drive Seeker, produce seeker_<MARK>_raw_output.json
├── refill.py                    # step 2: fill result* fields via java-scanner
├── run_all.sh                   # both steps
├── seeker_<MARK>_raw_output.json # produced by step 1, MARK from config.properties
├── seeker_<MARK>_output.json     # produced by step 2, comparable with main pipeline
└── README.md                    # this file

../../Seeker/                # upstream Seeker (untouched, treated as black box)
../../java-scanner/          # main pipeline; provides RefillFromExternal entry
```

The raw output filename includes `<MARK>` because Seeker's pipeline (via
`gpt_call.py`) reads `LLM_MODEL_NAME` from `java-scanner/config.properties` —
different LLM → different `methodResult`, so each model gets its own file
instead of overwriting the previous run's results.

The Seeker repo's source files (`Seeker/pipeline/*.py`) are NOT modified by
anything in this directory. The two pre-existing edits in `Seeker/pipeline/` —
fixing two real bugs in seeker.py and rewriting gpt_call.py to read the same
config.properties — happened before this directory existed and are not
maintained from here.

## Prerequisites

1. **Python with `openai` package** in your active env:
   ```bash
   pip install openai
   ```
   Seeker's full requirements.txt also pins pytorch/tensorflow but those are only
   used by Seeker's evaluate.py, which we don't run.

2. **java-scanner compiled** (refill calls into it):
   ```bash
   cd ../../java-scanner
   mvn -DskipTests package dependency:copy-dependencies
   ```

3. **LLM credentials** in `../../java-scanner/config.properties`. Seeker's
   `gpt_call.py` reads `LLM_API_URL` / `LLM_API_KEY` / `LLM_MODEL_NAME` from
   that same file, so switching models for the main method also switches them
   for Seeker.

## Usage

### Smoke (first 5 samples)
```bash
python wrapper_seeker.py --limit 5
python refill.py
# or:
./run_all.sh --limit 5
```

### Full run
```bash
./run_all.sh
```
Produces (with MARK from `config.properties`'s `EXPERIMENT_MARK`, e.g. `deepseek-v4-flash`):
- `seeker_<MARK>_raw_output.json` — Seeker's outputs, no result* fields yet
- `seeker_<MARK>_output.json` — refilled, comparable with `data/<MARK>_output.json`

### Resume after interruption
```bash
./run_all.sh --resume
```
Skips samples whose `(repo_id, patch, methodName)` already appear in
`seeker_<MARK>_raw_output.json` with non-empty `methodResult`.

## Comparing to the main method

Both pipelines now produce JSONs with identical schema (after refill):
```
data/<MARK>_output.json                              # main pipeline
baselines/seeker/seeker_<MARK>_output.json           # seeker baseline
```

Load both into a notebook and compute precision / recall / F1 / FPR over `label`
vs `changed`, and compare `resultExceptionTypes` / `resultCatchBlocks` against
ground truth `exceptionTypes` / `catchBlocks`.

## Cost notes

Per sample Seeker invokes the LLM ~7-10 times (5-agent pipeline, some agents
loop over units). Full run = ~10k-15k LLM calls, several hours. With
`deepseek-v4-flash` this is on the order of US$5-15. With GPT-4o it's ~50x more.

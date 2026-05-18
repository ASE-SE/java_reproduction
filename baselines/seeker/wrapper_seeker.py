#!/usr/bin/env python3
"""
Run the Seeker baseline (https://github.com/sssszh/Seeker) over the 1594-method
sample set, treating Seeker as a black box: feed each sample's `methodBefore`
into Seeker's pipeline and collect the resulting optimized code as
`methodResult`. The Seeker source itself is NOT modified by this wrapper.

Output JSON lives in this directory (baselines/seeker/), not under data/, to
keep all Seeker artifacts colocated with the wrapper that produced them.

After this script finishes, the per-sample dicts have:
  - all original fields from 1594_input_data.json (ground truth + locators)
  - methodResult: Seeker's optimized code (string)
  - result* fields are still EMPTY/zero — they get filled by refill.py via
    java-scanner's MethodDiffAnalyzer, so metrics are computed by the same
    extractor as the main method.

Usage:
    python wrapper_seeker.py                   # run with defaults
    python wrapper_seeker.py --limit 5         # only first 5 samples (smoke)
    python wrapper_seeker.py --resume          # skip samples already in output

Resume semantics: a sample is "done" if (repo_id, patch, methodName) appears in
the existing output JSON with non-empty methodResult.
"""
import argparse
import json
import os
import re
import sys
import tempfile
import time
import traceback
from pathlib import Path

# --- paths (anchored on this script's location, not cwd) ---------------------
HERE = Path(__file__).resolve().parent                              # baselines/seeker/
JAVA_EXCEPTION = HERE.parent.parent                                 # project root
JAVA_SCANNER = JAVA_EXCEPTION / "java-scanner"
CONFIG_PROPERTIES = JAVA_SCANNER / "config.properties"
INPUT_JSON = JAVA_EXCEPTION / "data" / "1594_input_data.json"


def read_mark():
    """Read EXPERIMENT_MARK from java-scanner/config.properties.

    Seeker's gpt_call.py reads the same config file for LLM_MODEL_NAME, so the
    mark accurately identifies which LLM produced this run's methodResult.
    Different LLMs → different methodResult → different raw output file.
    """
    if not CONFIG_PROPERTIES.exists():
        return "unknown"
    for line in CONFIG_PROPERTIES.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^EXPERIMENT_MARK\s*=\s*(.+?)\s*$", line)
        if m and not line.lstrip().startswith("#"):
            return m.group(1)
    return "unknown"


DEFAULT_OUTPUT = HERE / f"seeker_{read_mark()}_raw_output.json"     # before refill


def _resolve_seeker_pipeline():
    """Locate Seeker's pipeline/ directory.

    Override with env var SEEKER_PIPELINE if vendored elsewhere. Otherwise
    search a few sensible locations: alongside this wrapper, or as a sibling
    of the project root.
    """
    env = os.environ.get("SEEKER_PIPELINE")
    if env:
        return Path(env).expanduser().resolve()
    candidates = [
        HERE / "Seeker" / "pipeline",            # vendored under baselines/seeker/
        JAVA_EXCEPTION / "Seeker" / "pipeline",  # vendored at project root
        JAVA_EXCEPTION.parent / "Seeker" / "pipeline",  # sibling of project root
    ]
    for c in candidates:
        if (c / "seeker.py").exists():
            return c
    return candidates[0]  # return first as a hint for the error message


SEEKER_PIPELINE = _resolve_seeker_pipeline()
DEFAULT_CEE = SEEKER_PIPELINE / "cee.json"

# Allow `from seeker import main as seeker_main` to find Seeker's sources
sys.path.insert(0, str(SEEKER_PIPELINE))


def load_seeker_main():
    """Lazy import so missing openai package gives a clearer error message."""
    if not (SEEKER_PIPELINE / "seeker.py").exists():
        sys.exit(
            f"Seeker pipeline source not found at {SEEKER_PIPELINE}\n"
            f"Set the SEEKER_PIPELINE env var to point at the directory that "
            f"contains seeker.py / prompt.py / gpt_call.py / cee.json, or "
            f"vendor those files into one of the searched locations."
        )
    try:
        from seeker import main as seeker_main  # noqa: WPS433
    except ModuleNotFoundError as e:
        sys.exit(
            f"missing dependency: {e.name}\n"
            f"install in your active Python env:\n"
            f"  pip install openai\n"
            f"(Seeker's full requirements.txt also lists pytorch/tensorflow but "
            f"those are only needed by evaluate.py, which we don't run.)"
        )
    return seeker_main


def existing_keys(out_path):
    """Return set of (repo_id, patch, methodName) already in output, with non-empty methodResult."""
    if not out_path.exists():
        return set()
    try:
        existing = json.loads(out_path.read_text())
    except (json.JSONDecodeError, OSError):
        return set()
    keys = set()
    for r in existing:
        if r.get("methodResult"):
            keys.add((r.get("repo_id"), r.get("patch"), r.get("methodName")))
    return keys


def atomic_write_json(path, data):
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2))
    tmp.replace(path)


def run_one(sample, seeker_main, cee_path, capture_intermediate=False):
    """Run Seeker on a single sample.

    Returns the sample dict with methodResult set, or None on missing-methodBefore.
    Raises on any error inside seeker_main (caller handles).

    If `capture_intermediate=True`, also reads Seeker's sidecar JSON (per-unit
    matched_branches + selected_exceptions) and aggregates to two sample-level
    fields:
      detector_fires:  bool — any unit's matched_branches non-empty
      seeker_top1_exception:  str|None — exception with highest LikelihoodScore
                              across all units' selected_exceptions (kept for
                              future analysis; current pipeline uses AST types).
      seeker_top1_score:      float|None — its score
    """
    method_before = sample.get("methodBefore")
    if not method_before:
        return None

    # Per-call unique temp paths. mkstemp guarantees uniqueness even across threads.
    in_fd, in_path = tempfile.mkstemp(suffix=".java", prefix="seeker_in_")
    os.close(in_fd)
    out_fd, out_path = tempfile.mkstemp(suffix=".java", prefix="seeker_out_")
    os.close(out_fd)
    sidecar_path = None
    if capture_intermediate:
        sc_fd, sidecar_path = tempfile.mkstemp(suffix=".json", prefix="seeker_sc_")
        os.close(sc_fd)
    try:
        Path(in_path).write_text(method_before, encoding="utf-8")
        seeker_main(in_path, out_path, str(cee_path), sidecar_path)
        optimized = Path(out_path).read_text(encoding="utf-8").strip()
        sidecar = json.loads(Path(sidecar_path).read_text(encoding="utf-8")) if sidecar_path else []
    finally:
        for p in (in_path, out_path, sidecar_path):
            if not p:
                continue
            try:
                os.remove(p)
            except OSError:
                pass

    out = dict(sample)
    out["methodResult"] = optimized
    if capture_intermediate:
        # Aggregate per-sample signals from per-unit sidecar.
        detector_fires = any((u.get("matched_branches") or []) for u in sidecar)
        all_selected = []
        for u in sidecar:
            all_selected.extend(u.get("selected_exceptions") or [])
        if all_selected:
            top1 = max(all_selected, key=lambda e: e.get("LikelihoodScore", 0) or 0)
            out["seeker_top1_exception"] = top1.get("ExceptionType")
            out["seeker_top1_score"] = top1.get("LikelihoodScore")
        else:
            out["seeker_top1_exception"] = None
            out["seeker_top1_score"] = None
        out["detector_fires"] = bool(detector_fires)
    # leave result* / changed empty — refill.py fills them via MethodDiffAnalyzer
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input",  default=str(INPUT_JSON), help="input sample JSON")
    ap.add_argument("--output", default=str(DEFAULT_OUTPUT), help="output (raw, pre-refill) JSON")
    ap.add_argument("--cee",    default=str(DEFAULT_CEE), help="Seeker's cee.json path")
    ap.add_argument("--limit",  type=int, default=0, help="only run first N samples (0 = all)")
    ap.add_argument("--resume", action="store_true", help="skip samples already in output JSON")
    ap.add_argument("--workers", type=int, default=1,
                    help="concurrent LLM calls (default 1 = sequential). 10 recommended for "
                         "deepseek-v4-flash if rate limits allow.")
    ap.add_argument("--capture-intermediate", action="store_true",
                    help="capture per-sample detector_fires + seeker_top1_exception. "
                         "Requires the patched Seeker/pipeline/seeker.py that accepts "
                         "the 4th sidecar_path argument.")
    args = ap.parse_args()

    out_path = Path(args.output)
    seeker_main = load_seeker_main()

    print(f"input  : {args.input}")
    print(f"output : {args.output}")
    print(f"cee    : {args.cee}")
    print(f"workers: {args.workers}")
    print(f"capture: {args.capture_intermediate}")
    samples = json.loads(Path(args.input).read_text())
    print(f"samples: {len(samples)}")

    skip = existing_keys(out_path) if args.resume else set()
    if skip:
        print(f"resume : skipping {len(skip)} already-done samples")

    # Load existing output (resume) so we append, not overwrite.
    # Drop entries with empty methodResult — they were partial-failure attempts
    # in a prior run and need to be retried; keeping them would create duplicates.
    if args.resume and out_path.exists():
        try:
            results = [r for r in json.loads(out_path.read_text()) if r.get("methodResult")]
        except json.JSONDecodeError:
            results = []
    else:
        results = []

    # Build the "to-run" list once. Each item is paired with its position in
    # samples[] (i) and its identity key, so concurrent workers can't accidentally
    # process duplicates.
    to_run = []
    for i, sample in enumerate(samples):
        if args.limit and len(to_run) >= args.limit:
            break
        key = (sample.get("repo_id"), sample.get("patch"), sample.get("methodName"))
        if key in skip:
            continue
        to_run.append((i, key, sample))
    print(f"to process: {len(to_run)}")

    if args.workers <= 1:
        _run_sequential(to_run, results, samples, out_path, seeker_main, args, skip)
    else:
        _run_concurrent(to_run, results, samples, out_path, seeker_main, args, skip)


def _run_sequential(to_run, results, samples, out_path, seeker_main, args, skip):
    started = time.time()
    failed = 0
    for i, key, sample in to_run:
        try:
            out = run_one(sample, seeker_main, Path(args.cee),
                          capture_intermediate=args.capture_intermediate)
        except Exception:
            failed += 1
            print(f"[{i+1}/{len(samples)}] FAIL {key[2]} in {key[0]}")
            traceback.print_exc(limit=2)
            continue
        if out is None:
            continue
        results.append(out)
        if len(results) % 10 == 0:
            atomic_write_json(out_path, results)
        elapsed = time.time() - started
        print(f"[{i+1}/{len(samples)}] OK   {key[2]} in {key[0]}  "
              f"({len(results)} written, {failed} failed, {elapsed:.0f}s)")
    atomic_write_json(out_path, results)
    print(f"\ndone. wrote {len(results)} entries to {out_path}, {failed} failed.")
    print(f"next step: python refill.py {out_path}")


def _run_concurrent(to_run, results, samples, out_path, seeker_main, args, skip):
    """ThreadPoolExecutor-based fan-out.

    Concurrency safety:
    - results list is appended under a lock
    - out_path writes go through atomic_write_json (writes a .tmp then renames),
      protected by the same lock so two threads can't interleave on the buffer
    - each run_one() uses tempfile.mkstemp() which guarantees unique paths
      across threads (and processes) on POSIX, so per-call IO never collides
    - failures don't kill the worker pool; they get retried serially at the end
    """
    import threading
    from concurrent.futures import ThreadPoolExecutor, as_completed

    results_lock = threading.Lock()
    failures = []
    last_flush = [time.time()]

    def worker(item):
        i, key, sample = item
        try:
            out = run_one(sample, seeker_main, Path(args.cee),
                          capture_intermediate=args.capture_intermediate)
            return (i, key, out, None)
        except Exception as e:
            return (i, key, None, traceback.format_exc())

    started = time.time()
    completed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(worker, item): item for item in to_run}
        for fut in as_completed(futures):
            i, key, out, err = fut.result()
            completed += 1
            with results_lock:
                if out is not None:
                    results.append(out)
                if err is not None:
                    failures.append((i, key, err))
                # Flush every 20 done OR every 30s wall, whichever first.
                if len(results) % 20 == 0 or time.time() - last_flush[0] > 30:
                    atomic_write_json(out_path, results)
                    last_flush[0] = time.time()
            elapsed = time.time() - started
            sys.stdout.write(
                f"\r[{completed}/{len(to_run)}] results={len(results)} "
                f"failures={len(failures)} elapsed={elapsed:.0f}s   "
            )
            sys.stdout.flush()

    print()  # newline after carriage-return progress

    # Retry failures once, sequentially, so we can see traces clearly.
    if failures:
        print(f"\nretrying {len(failures)} failed samples serially...")
        retry_failed = []
        for i, key, err_first in failures:
            sample = samples[i]
            try:
                out = run_one(sample, seeker_main, Path(args.cee),
                              capture_intermediate=args.capture_intermediate)
                if out is not None:
                    results.append(out)
                    print(f"  RECOVERED: {key[2]} in {key[0]}")
            except Exception:
                retry_failed.append((i, key))
                print(f"  STILL FAILED: {key[2]} in {key[0]}")
                traceback.print_exc(limit=2)
        failures = retry_failed

    atomic_write_json(out_path, results)

    # Coverage assertion: we MUST end up with skip + results + remaining_failures
    # == len(to_run) + len(skip).
    expected_total = len(skip) + len(to_run)
    actual_total = len(skip) + len(results) + len(failures)
    if actual_total != expected_total:
        print(f"\nWARN: coverage mismatch — expected {expected_total} samples touched, "
              f"got {actual_total} (skip={len(skip)} results={len(results)} "
              f"failures={len(failures)})")
    elapsed = time.time() - started
    print(f"\ndone. wrote {len(results)} entries to {out_path}, "
          f"{len(failures)} unrecovered failures, {elapsed:.0f}s total.")
    print(f"next step: python refill.py {out_path}")


if __name__ == "__main__":
    main()

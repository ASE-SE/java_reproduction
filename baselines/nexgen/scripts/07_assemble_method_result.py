#!/usr/bin/env python3
"""
Assemble nexgen's task1 per-line predictions + task2 generated catch blocks
into a methodResult-style JSON compatible with refill.py + java-scanner's
MethodDiffAnalyzer.

Inputs:
  --input_1594        path to data/1594_input_data.json (the test set)
  --task1_pred        per_line_pred.json from train_task1.py predict_dump
                       (list of {per_line_pred, per_line_truth}, in 1594 order)
  --task2_pred        task2_pred.txt   (one generated catch block per
                       label=1 sample in 1594; matches the order produced
                       by 03_prep_task2.py test)
  --out               output JSON path (e.g. nexgen_raw_output.json)
  --config_label      label string to record in metadata (e.g. "nexgen" or "retrain")

The task1 predictions cover all 1594 samples (positives + negatives), in the
same row order as 1594_input_data.json (drop unprocessable / skipped rows
gets handled by us at prep time; we keep that ordering aligned).

The task2 predictions only exist for label=1 samples. We iterate 1594 in
order; for each label=1 sample we pop the next task2 line.

Output format (per sample):
  Original 1594 fields + new:
    "methodResult"          full methodAfter-style string (wrapped if task1 said so)
    "changed"               1 if task1 predicted any wrap-line, else 0
    "resultExceptionTypes"  exception types parsed from generated catch (or [])

Downstream refill.py will overwrite result*StartLine/EndLine/NoNestingLines
based on AST diff between methodBefore and methodResult, so we don't have to
fill those here.
"""
import argparse
import json
import re
import sys
from pathlib import Path


def wrap_try_catch(method_before, line_idxs, catch_block_text):
    """Wrap the specified lines (0-indexed within method_before) with
    try { ... } catch (...) { ... }.

    line_idxs is the list of line indices predicted as 1 by task1; we use
    [min..max] (inclusive) as the contiguous wrap span. Indents are derived
    from the first wrap line.
    """
    if not line_idxs:
        return method_before

    lines = method_before.split("\n")
    n = len(lines)
    line_idxs = [i for i in line_idxs if 0 <= i < n]
    if not line_idxs:
        return method_before

    start = min(line_idxs)
    end = max(line_idxs)
    # Skip line 0 if it's the method signature.
    if start == 0:
        # find next non-signature wrap line
        rest = [i for i in line_idxs if i > 0]
        if not rest:
            return method_before
        start, end = min(rest), max(rest)

    # leading whitespace from the first wrap line
    raw0 = lines[start]
    indent = raw0[: len(raw0) - len(raw0.lstrip(" \t"))]

    head = lines[:start]
    body = lines[start : end + 1]
    tail = lines[end + 1 :]

    # If task2 didn't give us anything usable, fall back to a TODO catch.
    catch_clause = (catch_block_text or "").strip()
    if not catch_clause or not catch_clause.lower().startswith("catch"):
        catch_clause = "catch (RuntimeException e) { /* TODO */ }"

    new_lines = (
        head
        + [f"{indent}try {{"]
        + ["  " + ln for ln in body]
        + [f"{indent}}} " + catch_clause]
        + tail
    )
    return "\n".join(new_lines)


_EXC_TYPE_RE = re.compile(r"catch\s*\(\s*([^)]+?)\s+\w+\s*\)")


def parse_exception_types(catch_text):
    """Extract list of exception types from a 'catch ( T1 | T2 e ) { ... }' string."""
    if not catch_text:
        return []
    m = _EXC_TYPE_RE.search(catch_text)
    if not m:
        return []
    types = [t.strip() for t in m.group(1).split("|")]
    return [t for t in types if t]


def assemble(input_1594, task1_pred_path, task2_pred_path, out_path, config_label):
    samples = json.load(open(input_1594))
    task1_preds = json.load(open(task1_pred_path))
    task2_lines = []
    if task2_pred_path and Path(task2_pred_path).exists():
        task2_lines = Path(task2_pred_path).read_text(encoding="utf-8").splitlines()
        # task2_pred uses space-tokenized output; convert to a string we can
        # use as a catch clause prefix. The line is e.g. "catch ( X e ) { ... }".
        task2_lines = [ln.strip() for ln in task2_lines]

    if len(task1_preds) != len(samples):
        sys.stderr.write(
            f"WARN: task1_pred count {len(task1_preds)} != samples {len(samples)}. "
            f"Will iterate by min(...).\n"
        )

    out = []
    t2_idx = 0
    for i, sample in enumerate(samples):
        rec = dict(sample)
        if i < len(task1_preds):
            pred = task1_preds[i]["per_line_pred"]
        else:
            pred = []
        positive_lines = [li for li, v in enumerate(pred) if v == 1]
        # Task1 strips empty lines during encoding; we mapped tokens line-by-line
        # so the indices here correspond to methodBefore lines AFTER stripping
        # empties. We approximate by re-aligning to non-empty lines in
        # methodBefore. (Empty lines retain label 0 which is harmless.)
        mb_lines = (sample.get("methodBefore") or "").split("\n")
        non_empty_to_orig = [i for i, ln in enumerate(mb_lines) if ln.strip()]
        original_positive_lines = []
        for idx in positive_lines:
            if idx < len(non_empty_to_orig):
                original_positive_lines.append(non_empty_to_orig[idx])

        if sample.get("label") == 1 and t2_idx < len(task2_lines):
            catch_text = task2_lines[t2_idx]
            t2_idx += 1
        else:
            catch_text = ""

        if original_positive_lines:
            mr = wrap_try_catch(sample.get("methodBefore") or "", original_positive_lines, catch_text)
            rec["methodResult"] = mr
            rec["changed"] = 1
            rec["resultExceptionTypes"] = parse_exception_types(catch_text)
        else:
            rec["methodResult"] = sample.get("methodBefore") or ""
            rec["changed"] = 0
            rec["resultExceptionTypes"] = []

        out.append(rec)

    Path(out_path).write_text(json.dumps(out, ensure_ascii=False, indent=2))
    print(f"wrote {len(out)} samples to {out_path}  (task2 lines consumed: {t2_idx})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input_1594", required=True)
    ap.add_argument("--task1_pred", required=True)
    ap.add_argument("--task2_pred", default="")
    ap.add_argument("--out", required=True)
    ap.add_argument("--config_label", default="nexgen")
    args = ap.parse_args()
    assemble(args.input_1594, args.task1_pred, args.task2_pred, args.out, args.config_label)


if __name__ == "__main__":
    main()

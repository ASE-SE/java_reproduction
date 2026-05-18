#!/usr/bin/env python3
"""
Prepare task1 (try-block localization) data for the three configurations:

  nexgen   — train/val from exp_data.csv (nexgen's own data). 90/10 split.
  retrain  — train/val from raw_mining (exception_addition_commits_deduplicated.json),
             with 1594 keys excluded. 90/10 split. All-positive at method level.
  test     — test set from 1594_input_data.json (552 pos + 1042 neg).

Output files (placed under <out_root>/<config>/):
  train.pkl  (DataFrame with columns ['lines', 'labels'])
  val.pkl    (same)
  test.pkl   (same; for 'test' config only)

Each row's `lines` is a list of code lines (one string per line, stripped of
empty lines just like nexgen's task1data.split_code_line); `labels` is a
list of 0/1 ints per line.

Usage:
  python 02_prep_task1.py nexgen  <exp_data.csv path>  <out_dir>
  python 02_prep_task1.py retrain <raw_mining.json>    <1594.json> <out_dir>
  python 02_prep_task1.py test    <1594.json>          <out_dir>
"""
import argparse
import json
import os
import random
import re
import sys
from pathlib import Path

import pandas as pd


SEED = 12345


def split_code_line_nexgen(source):
    """Exactly nexgen's task1data.split_code_line: strip try-catch syntax,
    label each remaining line by whether it was inside the try block."""
    source = str(source)
    source = re.sub(r"\Wtry\W\s*{", "###start###", source)
    source = re.sub(r"}\s*catch\W[\s\S]*?{[\s\S]*?}", "###end###", source)
    lines = re.split(r"\n+", source)
    results, labels = [], []
    flag = False
    for line in lines:
        line = line.strip()
        if not line:
            continue
        if line == "###start###":
            flag = True
        elif line == "###end###":
            flag = False
        else:
            results.append(line)
            labels.append(1 if flag else 0)
    return results, labels


def lines_and_labels_from_method_before(method_before, target_start, target_end):
    """For our dataset: input is methodBefore (no try-catch present), labels
    indicate which lines should be wrapped in [target_start, target_end] (both
    inclusive). For label=0 samples (no wrap target), pass target_start=None.

    Empty lines are stripped to match nexgen's preprocessing convention; labels
    are aligned to the non-empty lines via their original indices.
    """
    raw_lines = str(method_before).split("\n")
    wrap_set = set()
    if target_start is not None and target_end is not None:
        wrap_set = set(range(int(target_start), int(target_end) + 1))

    results, labels = [], []
    for i, line in enumerate(raw_lines):
        s = line.strip()
        if not s:
            continue
        results.append(s)
        labels.append(1 if i in wrap_set else 0)
    return results, labels


def split_90_10(rows):
    """Reproducible 90/10 train/val split."""
    rng = random.Random(SEED)
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    cut = int(0.9 * len(idx))
    return [rows[i] for i in idx[:cut]], [rows[i] for i in idx[cut:]]


def write_pkl(records, path):
    df = pd.DataFrame(records, columns=["lines", "labels"])
    df.to_pickle(path)
    print(f"  wrote {len(df):>7} rows  ->  {path}")


def prep_nexgen(exp_csv, out_dir):
    print(f"prep_nexgen: reading {exp_csv}")
    df = pd.read_csv(exp_csv)
    print(f"  total rows: {len(df)}")

    rows = []
    skipped = 0
    for i, code in enumerate(df["code"].tolist()):
        try:
            lines, labels = split_code_line_nexgen(code)
        except Exception:
            skipped += 1
            continue
        if not lines:
            skipped += 1
            continue
        rows.append((lines, labels))
        if (i + 1) % 50000 == 0:
            print(f"  processed {i + 1}/{len(df)} (kept {len(rows)}, skipped {skipped})")

    print(f"  kept {len(rows)}, skipped {skipped}")
    train, val = split_90_10(rows)
    os.makedirs(out_dir, exist_ok=True)
    write_pkl(train, os.path.join(out_dir, "train.pkl"))
    write_pkl(val, os.path.join(out_dir, "val.pkl"))


def prep_retrain(raw_mining_json, input_1594_json, out_dir):
    print(f"prep_retrain: reading {raw_mining_json}")
    raw = json.load(open(raw_mining_json))
    print(f"  raw_mining count: {len(raw)}")
    print(f"prep_retrain: reading {input_1594_json}")
    test_1594 = json.load(open(input_1594_json))
    print(f"  1594 count: {len(test_1594)}")

    # Exclude raw_mining entries that overlap with 1594 (by repo_id + methodName
    # + first-100-chars of methodBefore, a fuzzy-but-reasonable key).
    def fuzzy_key(x):
        mb = (x.get("methodBefore") or "")[:100]
        return (x.get("repo_id"), x.get("methodName"), mb)

    test_keys = {fuzzy_key(x) for x in test_1594}
    print(f"  1594 fuzzy keys (repo_id + methodName + methodBefore[:100]): {len(test_keys)}")

    rows = []
    skipped = 0
    excluded = 0
    for x in raw:
        if fuzzy_key(x) in test_keys:
            excluded += 1
            continue
        mb = x.get("methodBefore")
        tgt_start = x.get("beforeTargetStartLine")
        tgt_end = x.get("beforeTargetEndLine")
        if not mb or tgt_start is None or tgt_end is None:
            skipped += 1
            continue
        try:
            lines, labels = lines_and_labels_from_method_before(mb, tgt_start, tgt_end)
        except Exception:
            skipped += 1
            continue
        if not lines or sum(labels) == 0:
            # raw_mining is all-positive; if labels are all zero, the target
            # lines fell outside methodBefore — drop these (likely off-by-one
            # in the upstream extractor).
            skipped += 1
            continue
        rows.append((lines, labels))

    print(f"  excluded (1594 overlap, fuzzy): {excluded}")
    print(f"  skipped (missing fields / empty): {skipped}")
    print(f"  kept: {len(rows)}")

    train, val = split_90_10(rows)
    os.makedirs(out_dir, exist_ok=True)
    write_pkl(train, os.path.join(out_dir, "train.pkl"))
    write_pkl(val, os.path.join(out_dir, "val.pkl"))


def prep_test(input_1594_json, out_dir):
    print(f"prep_test: reading {input_1594_json}")
    data = json.load(open(input_1594_json))
    print(f"  total: {len(data)}")

    rows = []
    skipped = 0
    for x in data:
        mb = x.get("methodBefore")
        if not mb:
            skipped += 1
            continue
        label = x.get("label", -1)
        if label == 1:
            lines, labels = lines_and_labels_from_method_before(
                mb,
                x.get("beforeTargetStartLine"),
                x.get("beforeTargetEndLine"),
            )
        else:
            # label == 0 or -1 -> all-zero per-line labels
            lines, labels = lines_and_labels_from_method_before(mb, None, None)
        if not lines:
            skipped += 1
            continue
        rows.append((lines, labels))

    print(f"  kept: {len(rows)}, skipped: {skipped}")
    os.makedirs(out_dir, exist_ok=True)
    write_pkl(rows, os.path.join(out_dir, "test.pkl"))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_nexgen = sub.add_parser("nexgen", help="exp_data.csv -> train/val pkl")
    p_nexgen.add_argument("exp_csv")
    p_nexgen.add_argument("out_dir")

    p_retrain = sub.add_parser("retrain", help="raw_mining + 1594 -> train/val pkl")
    p_retrain.add_argument("raw_mining_json")
    p_retrain.add_argument("input_1594_json")
    p_retrain.add_argument("out_dir")

    p_test = sub.add_parser("test", help="1594 -> test pkl")
    p_test.add_argument("input_1594_json")
    p_test.add_argument("out_dir")

    args = ap.parse_args()
    if args.cmd == "nexgen":
        prep_nexgen(args.exp_csv, args.out_dir)
    elif args.cmd == "retrain":
        prep_retrain(args.raw_mining_json, args.input_1594_json, args.out_dir)
    elif args.cmd == "test":
        prep_test(args.input_1594_json, args.out_dir)


if __name__ == "__main__":
    main()

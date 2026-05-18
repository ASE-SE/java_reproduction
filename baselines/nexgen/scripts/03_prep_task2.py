#!/usr/bin/env python3
"""
Prepare task2 (catch-block generation) data. Produces space-tokenized text
file pairs (src-*.txt / tgt-*.txt) in the form nexgen's task2/prepare.py
expects.

  nexgen   — from exp_data.csv: filter to methods with try-catch, extract
             (source = whole tokenized method, target = catch block).
             90/10 train/valid split.
  retrain  — from raw_mining (exception_addition_commits_deduplicated.json)
             minus 1594, all are positives. Source uses methodAfter (has
             try-catch). Target reconstructs catch( type | type e ) { body }
             from catchBlocks + exceptionTypes.
  test     — from 1594_input_data.json label=1 only (552 samples). Same
             format as retrain.

Output layout per config:
  <out>/baseline/src-train.txt
  <out>/baseline/tgt-train.txt
  <out>/baseline/src-valid.txt
  <out>/baseline/tgt-valid.txt
  <out>/baseline/src-test.txt   (test config only)
  <out>/baseline/tgt-test.txt   (test config only)

After this script the user runs `python prepare.py` in nexgen's task2 dir to
generate the multi-slicing variants used by training.

Usage:
  python 03_prep_task2.py nexgen  <exp_data.csv>            <out_dir>
  python 03_prep_task2.py retrain <raw_mining.json> <1594.json> <out_dir>
  python 03_prep_task2.py test    <1594.json>              <out_dir>
"""
import argparse
import json
import os
import random
import re
import sys

import pandas as pd

try:
    import javalang
except ImportError:
    sys.exit(
        "missing dep: javalang\n"
        "  install via:  pip install javalang\n"
        "(only needed by this prep step; not by training)"
    )


SEED = 12345


def tokenize_java(code):
    """Return a list of token strings, or None on failure."""
    try:
        toks = list(javalang.tokenizer.tokenize(code))
        return [t.value for t in toks]
    except Exception:
        return None


def extract_catch_block_from_code(code):
    """Mimic task2data.cutout_catch: from a method with try-catch, return
    (source_without_try_catch, catch_block). Returns (None, None) on fail."""
    s = str(code)
    # Has exactly one try{} and one matching catch?
    if len(re.findall(r"}\s*catch\W", s)) != 1:
        return None, None
    if len(re.findall(r"\Wtry\W\s*{", s)) != 1:
        return None, None

    # Remove the try keyword + opening brace
    s_no_try = re.sub(r"\Wtry\W\s*{", "", s)
    targets = re.findall(r"}\s*(catch\W[\s\S]*?{[\s\S]*?})", s_no_try)
    if len(targets) != 1:
        return None, None
    # And strip everything from "} catch ..." onward to get the source
    source = re.sub(r"}\s*catch\W[\s\S]*", "", s_no_try)
    return source, targets[0]


_SURROGATE_RE = re.compile(r"[\ud800-\udfff]")


def _clean(tok):
    return _SURROGATE_RE.sub("�", tok)


def write_split(records, out_dir, split_name):
    """records: list of (src_tokens, tgt_tokens) where each is list[str]."""
    os.makedirs(out_dir, exist_ok=True)
    src_path = os.path.join(out_dir, f"src-{split_name}.txt")
    tgt_path = os.path.join(out_dir, f"tgt-{split_name}.txt")
    with open(src_path, "w", encoding="utf-8", errors="replace") as fs, \
         open(tgt_path, "w", encoding="utf-8", errors="replace") as ft:
        for src_toks, tgt_toks in records:
            fs.write(" ".join(_clean(t) for t in src_toks) + "\n")
            ft.write(" ".join(_clean(t) for t in tgt_toks) + "\n")
    print(f"  wrote {len(records):>7} pairs  ->  {src_path} + tgt-{split_name}.txt")


def split_90_10(rows):
    rng = random.Random(SEED)
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    cut = int(0.9 * len(idx))
    return [rows[i] for i in idx[:cut]], [rows[i] for i in idx[cut:]]


def prep_nexgen(exp_csv, out_dir):
    print(f"prep_nexgen: reading {exp_csv}")
    df = pd.read_csv(exp_csv)
    print(f"  total rows: {len(df)}")

    rows = []
    skipped_no_catch = 0
    skipped_token = 0
    for i, code in enumerate(df["code"].tolist()):
        _check, tgt_raw = extract_catch_block_from_code(code)
        if _check is None:
            skipped_no_catch += 1
            continue
        # nexgen's prepare.py expects src to contain the try keyword so it can
        # split front/back at the try position. Keep the full method as src.
        src_raw = str(code)
        src_toks = tokenize_java(src_raw)
        tgt_toks = tokenize_java(tgt_raw)
        if src_toks is None or tgt_toks is None or not src_toks or not tgt_toks:
            skipped_token += 1
            continue
        rows.append((src_toks, tgt_toks))
        if (i + 1) % 50000 == 0:
            print(f"  {i + 1}/{len(df)} processed, kept {len(rows)}")

    print(f"  skipped (no single try-catch match): {skipped_no_catch}")
    print(f"  skipped (tokenize failed):           {skipped_token}")
    print(f"  kept: {len(rows)}")
    train, val = split_90_10(rows)
    out_baseline = os.path.join(out_dir, "baseline")
    write_split(train, out_baseline, "train")
    write_split(val, out_baseline, "valid")


def reconstruct_catch(exception_types, catch_body):
    """Build a catch clause string from (exception_types: List[str], catch_body: str).
    Output: "catch ( T1 | T2 ... e ) { body... }"
    """
    types = [t.strip() for t in exception_types if t and t.strip()] or ["RuntimeException"]
    clause_types = " | ".join(types)
    body = catch_body.strip()
    # body is usually like "{\n  ...\n}\n"; strip outermost braces if present
    if body.startswith("{"):
        body = body[1:]
    if body.endswith("}"):
        body = body[:-1]
    return f"catch ( {clause_types} e ) {{ {body.strip()} }}"


def prep_retrain(raw_mining_json, input_1594_json, out_dir):
    print(f"prep_retrain: reading {raw_mining_json}")
    raw = json.load(open(raw_mining_json))
    print(f"  raw_mining count: {len(raw)}")
    test_1594 = json.load(open(input_1594_json))

    def fuzzy_key(x):
        mb = (x.get("methodBefore") or "")[:100]
        return (x.get("repo_id"), x.get("methodName"), mb)

    test_keys = {fuzzy_key(x) for x in test_1594}

    rows = []
    excluded = skipped = 0
    for i, x in enumerate(raw):
        if fuzzy_key(x) in test_keys:
            excluded += 1
            continue
        method_after = x.get("methodAfter")
        catch_blocks = x.get("catchBlocks") or []
        ex_types = x.get("exceptionTypes") or []
        if not method_after or not catch_blocks or not ex_types:
            skipped += 1
            continue

        # src needs to contain the try keyword for prepare.py — require methodAfter
        # to match the single try-catch pattern.
        _check, _ = extract_catch_block_from_code(method_after)
        if _check is None:
            skipped += 1
            continue
        src_raw = method_after

        tgt_raw = reconstruct_catch(ex_types, catch_blocks[0])

        src_toks = tokenize_java(src_raw)
        tgt_toks = tokenize_java(tgt_raw)
        if not src_toks or not tgt_toks:
            skipped += 1
            continue
        rows.append((src_toks, tgt_toks))
        if (i + 1) % 10000 == 0:
            print(f"  {i + 1}/{len(raw)} processed, kept {len(rows)}")

    print(f"  excluded (1594 overlap): {excluded}")
    print(f"  skipped (missing fields / tokenize fail): {skipped}")
    print(f"  kept: {len(rows)}")
    train, val = split_90_10(rows)
    out_baseline = os.path.join(out_dir, "baseline")
    write_split(train, out_baseline, "train")
    write_split(val, out_baseline, "valid")


def prep_test(input_1594_json, out_dir):
    print(f"prep_test: reading {input_1594_json}")
    data = json.load(open(input_1594_json))
    print(f"  total: {len(data)}, label=1: {sum(1 for x in data if x.get('label') == 1)}")

    rows = []
    skipped = 0
    for x in data:
        if x.get("label") != 1:
            continue
        method_after = x.get("methodAfter")
        catch_blocks = x.get("catchBlocks") or []
        ex_types = x.get("exceptionTypes") or []
        if not method_after or not catch_blocks or not ex_types:
            skipped += 1
            continue

        _check, _ = extract_catch_block_from_code(method_after)
        if _check is None:
            skipped += 1
            continue
        src_raw = method_after

        tgt_raw = reconstruct_catch(ex_types, catch_blocks[0])

        src_toks = tokenize_java(src_raw)
        tgt_toks = tokenize_java(tgt_raw)
        if not src_toks or not tgt_toks:
            skipped += 1
            continue
        rows.append((src_toks, tgt_toks))

    print(f"  kept: {len(rows)}, skipped: {skipped}")
    out_baseline = os.path.join(out_dir, "baseline")
    write_split(rows, out_baseline, "test")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("nexgen")
    p.add_argument("exp_csv")
    p.add_argument("out_dir")

    p = sub.add_parser("retrain")
    p.add_argument("raw_mining_json")
    p.add_argument("input_1594_json")
    p.add_argument("out_dir")

    p = sub.add_parser("test")
    p.add_argument("input_1594_json")
    p.add_argument("out_dir")

    args = ap.parse_args()
    if args.cmd == "nexgen":
        prep_nexgen(args.exp_csv, args.out_dir)
    elif args.cmd == "retrain":
        prep_retrain(args.raw_mining_json, args.input_1594_json, args.out_dir)
    elif args.cmd == "test":
        prep_test(args.input_1594_json, args.out_dir)


if __name__ == "__main__":
    main()

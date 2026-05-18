#!/usr/bin/env python3
"""
把 seeker 的 detector 中间信号合并到现有 AST refill 输出, 产出
seeker_detector_output.json. 设计意图: 只让 sample-level "需要 try-catch" 这一项
切到 Seeker Detector 信号, 其余字段 (resultExceptionTypes / result*Lines /
methodResult) 仍走 AST 口径 — 与 my / nexgen 一致, 且类型用 AST 解析比 Ranker
top-1 更准.

输入:
  --with-detector   wrapper_seeker.py --capture-intermediate 写出的新 raw JSON,
                    含 detector_fires + seeker_top1_exception(score)
  --ast-output      旧的 AST refill 产物 (seeker_<model>_output.json), 含所有
                    result*Lines / resultExceptionTypes / methodResult

字段融合:
  changed                          ← detector_fires (1/0)
  detector_fires                   ← 同上 (留痕)
  seeker_top1_exception(_score)    ← 留痕, 不参与 metric
  其它字段                          ← 全部从 AST refill 拷贝

Usage:
  python build_seeker_detector_output.py \\
      --with-detector seeker_deepseek-v4-flash_with_detector_raw.json \\
      --ast-output    seeker_deepseek-v4-flash_output.json \\
      --out           seeker_detector_output.json
"""
import argparse
import json
from pathlib import Path


def sample_key(x):
    return (x.get("repo_id"), x.get("patch"), x.get("methodName"))


SIGNAL_CHOICES = {
    "detector": "Detector agent 的 matched_branches 非空 (任一 unit)",
    "ranker":   "Ranker 经 LikelihoodScore>0.5 阈值后 selected_exceptions 非空 (任一 unit, 等价于 seeker_top1_exception 非 None)",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--with-detector", required=True)
    ap.add_argument("--ast-output", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument(
        "--signal",
        choices=list(SIGNAL_CHOICES),
        default="detector",
        help="哪个中间信号作为 changed: " + "; ".join(f"{k}={v}" for k, v in SIGNAL_CHOICES.items()),
    )
    args = ap.parse_args()
    print(f"signal: {args.signal} ({SIGNAL_CHOICES[args.signal]})")

    wd = json.load(open(args.with_detector))
    ast = json.load(open(args.ast_output))
    wd_map = {sample_key(x): x for x in wd}

    out = []
    missing = []
    fired = 0
    for rec in ast:
        k = sample_key(rec)
        w = wd_map.get(k)
        new_rec = dict(rec)
        if w is None:
            missing.append(k)
        else:
            if args.signal == "detector":
                signal_val = bool(w.get("detector_fires"))
            else:  # ranker
                signal_val = w.get("seeker_top1_exception") is not None
            new_rec["changed"] = 1 if signal_val else 0
            new_rec["detector_fires"] = w.get("detector_fires")
            new_rec["seeker_top1_exception"] = w.get("seeker_top1_exception")
            new_rec["seeker_top1_score"] = w.get("seeker_top1_score")
            # ranker 口径下, 异常类型也切到 Ranker top-1 (单一类型, 与 my / nexgen 公平比较)
            if args.signal == "ranker":
                top1 = w.get("seeker_top1_exception")
                new_rec["resultExceptionTypes"] = [top1] if top1 else []
        if new_rec.get("changed") == 1:
            fired += 1
        out.append(new_rec)

    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=2))
    print(f"wrote {len(out)} samples to {args.out}")
    print(f"  changed=1 (detector_fires): {fired}")
    print(f"  missing from with-detector (kept AST changed as-is): {len(missing)}")
    if missing:
        print(f"  first missing keys: {missing[:5]}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Nexgen 专用 hybrid evaluation 输出 — 把 model-pred (09_modelpred.py) 和 AST refill
(07_assemble + 08_refill) 的结果按"哪个口径合适"逐字段融合, 写出一个能直接喂
compare_methods.py 的 JSON.

字段分工
--------
来自 model-pred (task1/task2 模型直出, 反映 nexgen 检测能力本身):
  changed                            -> 控制 表1 sample 级 P/R/F1
  resultBeforeTargetStartLine
  resultBeforeTargetEndLine
  resultBeforeTargetNoNestingLines   -> 控制 表2/3 行级 P/R/F1 + 表5 威胁行覆盖
  resultAfterTarget{...}             -> 镜像 before (AST 不参与)

来自 AST refill (java-scanner 对生成 wrap 的语义解析, 类型抽取更可靠):
  resultExceptionTypes               -> 控制 表4 异常类型 hit_rate / micro P/R/F1
  resultCatchBlocks                  -> 留痕
  methodResult                       -> 真正的 wrap 字符串 (仅留痕)

AST refill 在 wrap 无法解析时返回空 resultExceptionTypes; 此时 fallback 到
model-pred 用正则从 task2 输出文本里抽出来的类型 (虽然不及 AST 干净, 但比空好).

使用前提
--------
1. 已有 nexgen_modelpred_output.json (scripts/09_modelpred.py 产出)
2. 已有 nexgen_output.json          (scripts/08_refill.py    产出)

Usage:
  python 10_hybrid_output.py \\
      --modelpred  nexgen_modelpred_output.json \\
      --refilled   nexgen_output.json \\
      --out        nexgen_hybrid_output.json
"""
import argparse
import json
from pathlib import Path


def fuse(modelpred_path, refilled_path, out_path):
    mp = json.load(open(modelpred_path))
    rf = json.load(open(refilled_path))
    assert len(mp) == len(rf), f"length mismatch: modelpred={len(mp)} refill={len(rf)}"

    out = []
    ast_filled_types = 0
    mp_fallback_types = 0
    for m, r in zip(mp, rf):
        # baseline fields are identical between mp and rf — just take from mp
        rec = dict(m)

        # 检测口径: 完全保留 model-pred (rec 已经是 m 的拷贝)
        # 类型口径: 优先用 AST refill 的解析结果
        ast_types = r.get("resultExceptionTypes") or []
        if ast_types:
            rec["resultExceptionTypes"] = ast_types
            rec["resultCatchBlocks"] = r.get("resultCatchBlocks") or []
            ast_filled_types += 1 if rec["changed"] == 1 else 0
        else:
            # fallback 已经在 rec 里了 (来自 model-pred 正则)
            mp_fallback_types += 1 if rec["changed"] == 1 else 0

        # methodResult 用 AST 版 (真实 wrap 字符串), 不影响指标计算但便于人审
        rec["methodResult"] = r.get("methodResult") or m.get("methodResult") or m.get("methodBefore", "")

        out.append(rec)

    Path(out_path).write_text(json.dumps(out, ensure_ascii=False, indent=2))
    fired = sum(1 for x in out if x.get("changed") == 1)
    print(f"wrote {len(out)} samples to {out_path}")
    print(f"  changed=1: {fired}")
    print(f"  types from AST refill: {ast_filled_types}  (AST 成功 parse 出 catch 类型)")
    print(f"  types from model-pred fallback: {mp_fallback_types}  (AST 失败, 正则兜底)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--modelpred", required=True)
    ap.add_argument("--refilled", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    fuse(args.modelpred, args.refilled, args.out)


if __name__ == "__main__":
    main()

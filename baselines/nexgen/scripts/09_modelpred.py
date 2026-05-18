#!/usr/bin/env python3
"""
直接用 nexgen 两个模型的原始输出 (task1 per_line_pred.json + task2 task2_pred.txt)
组装出与 compare_methods.py 兼容的 output JSON, **跳过 java-scanner refill** 这一步.

为什么需要这个: 07_assemble + 08_refill 的流水线对每个样本要求 wrap_try_catch 拼出的
Java 能被 javaparser 解析才认为 changed=1, 这会把 nexgen task1 实际预测为正、但
BIO span 落在签名/注释/嵌套块边界上的样本统统压成 changed=0. 对 nexgen 来说,
sample-level "这个方法是否需要 try-catch" 的真正信号在 task1 模型的 per-line 预测,
而非 AST 能否解析最终拼装结果. 我们把指标口径切回到"模型自己怎么说":

  changed                          = 1 if 任意一行 per_line_pred==1 (排除签名行) else 0
  resultBeforeTargetStartLine      = 第一个正预测行 (映射回原 methodBefore 行号)
  resultBeforeTargetEndLine        = 最后一个正预测行 + 1 (半开区间)
  resultBeforeTargetNoNestingLines = 所有正预测行的有序集合
  resultExceptionTypes             = 从 task2 catch 块文本解析出的异常类型 (仅 label==1
                                     样本能拿到 task2 输出, 按 1594 顺序对齐)
  resultAfter*                     = 镜像 resultBefore* (没有真正的 "after", AST 不参与)
  methodResult                     = methodBefore (无意义占位, 不参与 metric)

Usage:
  python 09_modelpred.py \\
      --input_1594 /path/to/1594_input_data.json \\
      --task1_pred /path/to/per_line_pred.json \\
      --task2_pred /path/to/task2_pred.txt \\
      --out        /path/to/nexgen_modelpred_output.json
"""
import argparse
import json
import re
from pathlib import Path


_EXC_RE = re.compile(r"catch\s*\(\s*([^)]+?)\s+\w+\s*\)")


def parse_exception_types(catch_text):
    """从 'catch ( IOException | SQLException e ) { ... }' 这种字符串里抽类型."""
    if not catch_text:
        return []
    m = _EXC_RE.search(catch_text)
    if not m:
        return []
    return [t.strip() for t in m.group(1).split("|") if t.strip()]


def map_positives_to_original_lines(per_line_pred, method_before):
    """task1 在编码阶段去除了空行, 这里把"非空行序号"映射回 methodBefore 原始行号."""
    mb_lines = (method_before or "").split("\n")
    non_empty = [i for i, ln in enumerate(mb_lines) if ln.strip()]
    positive_idx = [li for li, v in enumerate(per_line_pred) if v == 1]
    return [non_empty[idx] for idx in positive_idx if idx < len(non_empty)]


def build(input_1594, task1_pred_path, task2_pred_path, out_path):
    samples = json.load(open(input_1594))
    task1_preds = json.load(open(task1_pred_path))
    task2_lines = (
        Path(task2_pred_path).read_text(encoding="utf-8").splitlines()
        if task2_pred_path and Path(task2_pred_path).exists()
        else []
    )
    task2_lines = [ln.strip() for ln in task2_lines]

    out = []
    t2_idx = 0
    for i, sample in enumerate(samples):
        rec = dict(sample)
        pred = task1_preds[i]["per_line_pred"] if i < len(task1_preds) else []
        original_positives = map_positives_to_original_lines(pred, sample.get("methodBefore"))
        # 永远不把方法签名 (line 0) 视作"需要被 wrap"的目标行
        original_positives = sorted({li for li in original_positives if li > 0})

        # task2 只对 label==1 样本预测; 按 1594 顺序顺次配对
        if sample.get("label") == 1 and t2_idx < len(task2_lines):
            catch_text = task2_lines[t2_idx]
            t2_idx += 1
        else:
            catch_text = ""

        if original_positives:
            start = original_positives[0]
            end = original_positives[-1] + 1  # 半开区间
            rec["changed"] = 1
            rec["resultBeforeTargetStartLine"] = start
            rec["resultBeforeTargetEndLine"] = end
            rec["resultBeforeTargetNoNestingLines"] = original_positives
            rec["resultAfterTargetStartLine"] = start
            rec["resultAfterTargetEndLine"] = end
            rec["resultAfterTargetNoNestingLines"] = original_positives
            rec["resultExceptionTypes"] = parse_exception_types(catch_text)
            rec["resultCatchBlocks"] = [catch_text] if catch_text else []
        else:
            rec["changed"] = 0
            rec["resultBeforeTargetStartLine"] = 0
            rec["resultBeforeTargetEndLine"] = 0
            rec["resultBeforeTargetNoNestingLines"] = []
            rec["resultAfterTargetStartLine"] = 0
            rec["resultAfterTargetEndLine"] = 0
            rec["resultAfterTargetNoNestingLines"] = []
            rec["resultExceptionTypes"] = []
            rec["resultCatchBlocks"] = []
        # methodResult 在此 pipeline 下无意义 — 用 methodBefore 占位,
        # 后续如需做生成质量评估再走 07_assemble + 08_refill 那条路.
        rec["methodResult"] = sample.get("methodBefore") or ""

        out.append(rec)

    Path(out_path).write_text(json.dumps(out, ensure_ascii=False, indent=2))
    fired = sum(1 for x in out if x.get("changed") == 1)
    print(f"wrote {len(out)} samples to {out_path}")
    print(f"  changed=1: {fired}  task2 catch lines consumed: {t2_idx}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input_1594", required=True)
    ap.add_argument("--task1_pred", required=True)
    ap.add_argument("--task2_pred", default="")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    build(args.input_1594, args.task1_pred, args.task2_pred, args.out)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
对比多种方法（你的方法 vs 各 baseline）的指标。

所有方法的输出 JSON 都通过 java-scanner 的 RefillFromExternal 生成，
schema 相同 (label / changed / result* / beforeTarget* 等)，因此可以
直接横向比较。

用法
----
  默认 (用下面 METHODS dict 跑):
    python compare_methods.py

  临时指定方法 (覆盖 METHODS):
    python compare_methods.py --method my:data/deepseek-v4-flash_output.json \\
                              --method seeker:baselines/seeker/seeker_deepseek-v4-flash_output.json

  只看部分方法:
    python compare_methods.py --only my,seeker

  写 Markdown 表到文件:
    python compare_methods.py --md compare.md

加新 baseline 的步骤
--------------------
1) 在 METHODS dict 里加一行: "your_baseline_name": "<path-to-output.json>"
2) python compare_methods.py
就这样。
"""

import argparse
import json
from pathlib import Path


# ============================================================================
# 配置: 加 baseline 只改这里
# 路径相对项目根 (java_exception/)，或写绝对路径。
# ============================================================================
METHODS = {
    "my":             "data/deepseek-v4-flash_output.json",
    "my-qwen":        "data/qwen-max_output.json",
    "seeker":         "baselines/seeker/seeker_deepseek-v4-flash_output.json",
    # seeker-detector: changed 信号来自 Seeker Detector agent 的 matched_branches.
    # seeker-ranker:   changed 信号来自 Ranker 经 LikelihoodScore>0.5 阈值后 selected_exceptions 非空.
    # 两者其它字段 (类型 / 行级 / 威胁覆盖) 仍走 AST. 由 build_seeker_detector_output.py 生成.
    "seeker-detector": "baselines/seeker/seeker_detector_output.json",
    "seeker-ranker":   "baselines/seeker/seeker_ranker_output.json",
    "seeker-qwen":          "baselines/seeker/seeker_qwen-max_output.json",
    "seeker-qwen-detector": "baselines/seeker/seeker_qwen_detector_output.json",
    "seeker-qwen-ranker":   "baselines/seeker/seeker_qwen_ranker_output.json",
    "neurex":         "baselines/Neurex/neurex_output.json",
    # Nexgen 走 AST refill 一致口径 (与 my/seeker/neurex 同一套抽取规则).
    # 备选: scripts/09_modelpred.py 直出, scripts/10_hybrid_output.py 融合 —
    # 文件位于 baselines/nexgen/{nexgen,retrain}_{modelpred,hybrid}_output.json,
    # 需要 detection-only 或 hybrid 口径时可改指过去, 无需重新生成.
    "nexgen-原数据集":   "baselines/nexgen/nexgen_output.json",
    "nexgen-我们的数据集": "baselines/nexgen/retrain_output.json",
    # 未来加 baseline 直接在下面加行即可:
    # "llm_only_qwen": "data/experiment_result_clean-blllm_qwen.json",
}
# ============================================================================


PROJECT_ROOT = Path(__file__).resolve().parent.parent   # java_exception/


def load(path):
    p = Path(path)
    if not p.is_absolute():
        p = PROJECT_ROOT / p
    with open(p, encoding="utf-8") as f:
        return json.load(f)


# ---------- 指标定义 (与 result_manager.ipynb 一致) -------------------------

def _confusion_derived(tp, fp, fn, tn):
    """从 TP/FP/FN/TN 派生 6 个分类指标"""
    p   = tp / (tp + fp) if tp + fp else 0     # Precision  精确率
    r   = tp / (tp + fn) if tp + fn else 0     # Recall     召回率
    f1  = 2 * p * r / (p + r) if p + r else 0  # F1
    fpr = fp / (fp + tn) if fp + tn else 0     # FPR        误报率
    fnr = fn / (tp + fn) if tp + fn else 0     # FNR        漏报率 (= 1 - R)
    acc = (tp + tn) / (tp + fp + fn + tn) if (tp + fp + fn + tn) else 0  # Accuracy
    return dict(P=p, R=r, F1=f1, FPR=fpr, FNR=fnr, Acc=acc,
                TP=tp, FP=fp, FN=fn, TN=tn)


def m_sample(data):
    """家族 1: 样本级 — label vs changed>0
    "工具有没有判定该方法需要新增 try-catch" """
    tp = fp = fn = tn = 0
    for it in data:
        l = it.get("label", -1) == 1
        c = (it.get("changed") or 0) > 0
        if l and c: tp += 1
        elif l and not c: fn += 1
        elif not l and c: fp += 1
        else: tn += 1
    return _confusion_derived(tp, fp, fn, tn)


def m_line_no_nesting(data):
    """家族 2: 行级 (不计嵌套) — beforeTargetNoNestingLines vs resultBeforeTargetNoNestingLines
    TN 用 method_length - TP - FP - FN 估计 (与原 notebook 一致, 把方法所有行视为可能行)
    仅在 label==1 的样本上累积。
    """
    TP = FP = FN = TN = 0
    for it in data:
        if it.get("label", -1) != 1:
            continue
        true_set = set(it.get("beforeTargetNoNestingLines") or [])
        pred_set = set(it.get("resultBeforeTargetNoNestingLines") or [])
        tp_i = len(true_set & pred_set)
        fn_i = len(true_set - pred_set)
        fp_i = len(pred_set - true_set)
        method_length = (it.get("methodBefore") or "").count("\n")
        tn_i = max(0, method_length - tp_i - fp_i - fn_i)
        TP += tp_i; FP += fp_i; FN += fn_i; TN += tn_i
    return _confusion_derived(TP, FP, FN, TN)


def m_line_range(data):
    """家族 3: 行级 (按 [start, end) 区间) — label==1 子集
    TN 用 method_length - TP - FP - FN 估计。
    """
    TP = FP = FN = TN = 0
    for it in data:
        if it.get("label", -1) != 1:
            continue
        ts, te = it.get("beforeTargetStartLine"), it.get("beforeTargetEndLine")
        ps, pe = it.get("resultBeforeTargetStartLine"), it.get("resultBeforeTargetEndLine")
        if ts is None or te is None or ps is None or pe is None:
            continue
        true_set = set(range(ts, te))
        pred_set = set(range(ps, pe))
        tp_i = len(true_set & pred_set)
        fn_i = len(true_set - pred_set)
        fp_i = len(pred_set - true_set)
        method_length = (it.get("methodBefore") or "").count("\n")
        tn_i = max(0, method_length - tp_i - fp_i - fn_i)
        TP += tp_i; FP += fp_i; FN += fn_i; TN += tn_i
    return _confusion_derived(TP, FP, FN, TN)


def _expand_exception_types(items):
    """resultExceptionTypes 中元素可能是 'Foo|Bar' 形式 (multi-catch), 拆开成单个类型集合。"""
    out = set()
    for e in items or []:
        out.update(e.split("|"))
    return out


def m_type_hit_in_tp(data):
    """家族 4: TP 子集 (label=1 ∧ changed>0) — "至少猜中一种异常类型" 的样本占比。"""
    matched = total = 0
    for it in data:
        if it.get("label", -1) != 1 or (it.get("changed") or 0) <= 0:
            continue
        total += 1
        true_t = set(it.get("exceptionTypes") or [])
        pred_t = _expand_exception_types(it.get("resultExceptionTypes"))
        if true_t & pred_t:
            matched += 1
    return dict(matched=matched, total=total, rate=matched / total if total else 0)


def m_type_set_prf(data):
    """家族 4b: TP 子集 — 异常类型 micro P/R/F1。
    把每个 TP 样本的 (true_types, pred_types) 视作一个多标签预测; 跨样本累加
    TP/FP/FN, 计算 micro 平均的 Precision/Recall/F1。
    """
    TP = FP = FN = 0
    total = 0
    for it in data:
        if it.get("label", -1) != 1 or (it.get("changed") or 0) <= 0:
            continue
        total += 1
        true_t = set(it.get("exceptionTypes") or [])
        pred_t = _expand_exception_types(it.get("resultExceptionTypes"))
        TP += len(true_t & pred_t)
        FP += len(pred_t - true_t)
        FN += len(true_t - pred_t)
    P = TP / (TP + FP) if (TP + FP) else 0
    R = TP / (TP + FN) if (TP + FN) else 0
    F1 = 2 * P * R / (P + R) if (P + R) else 0
    return dict(P=P, R=R, F1=F1, TP=TP, FP=FP, FN=FN, total=total)


def m_threat_coverage_in_tp(data):
    """家族 5: TP 子集 — 威胁代码行覆盖
       真实威胁代码行 = beforeTargetNoNestingLines
       预测覆盖行     = [resultStart, resultEnd) ∪ resultBeforeTargetNoNestingLines

       派生 5 个指标:
         start_in_pred_rate   起始行被包含率
         half_caught_rate     ≥50% 覆盖且起始行被包 的样本占比 (核心 "catch 住" 指标)
         full_covered_rate    100% 完全覆盖率
         avg_coverage         所有 TP 样本的平均覆盖率 (= 召回向)
         avg_hit_precision    所有 TP 样本的平均 "命中精度" (= |pred ∩ true| / |pred|, 精确向);
                              低 = 工具圈了很多无关行 ("过覆盖"). 与 avg_coverage 配合
                              可以揭穿 "圈整个方法刷威胁覆盖" 的退化策略.
    """
    total = 0
    start_in = 0
    half_caught = 0
    full_cover = 0
    cov_sum = 0.0
    hit_prec_sum = 0.0
    for it in data:
        l = it.get("label", -1) == 1
        c = (it.get("changed") or 0) > 0
        if not (l and c):
            continue
        ps, pe = it.get("resultBeforeTargetStartLine"), it.get("resultBeforeTargetEndLine")
        if ps is None or pe is None:
            continue
        true_lines = it.get("beforeTargetNoNestingLines") or []
        if not true_lines:
            continue
        total += 1
        pred_cover = set(range(ps, pe)) | set(it.get("resultBeforeTargetNoNestingLines") or [])
        hit = sum(1 for ln in true_lines if ln in pred_cover)
        coverage = hit / len(true_lines)
        cov_sum += coverage
        if pred_cover:
            hit_prec_sum += hit / len(pred_cover)
        true_start = it.get("beforeTargetStartLine")
        if true_start is not None and true_start in pred_cover:
            start_in += 1
            if coverage >= 0.5:
                half_caught += 1
        if coverage >= 1.0:
            full_cover += 1
    return dict(
        total=total,
        start_in=start_in, start_in_rate=start_in / total if total else 0,
        half_caught=half_caught, half_caught_rate=half_caught / total if total else 0,
        full_cover=full_cover, full_cover_rate=full_cover / total if total else 0,
        avg_coverage=cov_sum / total if total else 0,
        avg_hit_precision=hit_prec_sum / total if total else 0,
    )


# ---------- 渲染 ------------------------------------------------------------

CLS_TITLES = {
    "sample":     "1. 样本级 (label vs changed>0)",
    "line_nn":    "2. 行级 (不计嵌套, NoNestingLines)  — 仅 label=1 累积",
    "line_range": "3. 行级 (按 [start,end) 区间)       — 仅 label=1 累积",
}

def _classification_block_text(title, rows, key):
    out = ["", title, "-" * 78,
           f"  {'method':12s}  {'P':>7s} {'R':>7s} {'F1':>7s} {'FPR':>7s} {'FNR':>7s} {'Acc':>7s}     TP/FP/FN/TN"]
    for name, r in rows.items():
        m = r[key]
        out.append(f"  {name:12s}  "
                   f"{m['P']:.4f} {m['R']:.4f} {m['F1']:.4f} "
                   f"{m['FPR']:.4f} {m['FNR']:.4f} {m['Acc']:.4f}     "
                   f"{m['TP']}/{m['FP']}/{m['FN']}/{m['TN']}")
    return out


def render_text(rows):
    out = ["=" * 78]
    for key in ("sample", "line_nn", "line_range"):
        out += _classification_block_text(CLS_TITLES[key], rows, key)

    out += ["", "4. TP 子集: 异常类型命中率 (resultExceptionTypes ∩ exceptionTypes 非空)",
            "-" * 78,
            f"  {'method':12s}  matched / total = rate"]
    for name, r in rows.items():
        m = r["type_in_tp"]
        out.append(f"  {name:12s}  {m['matched']} / {m['total']} = {m['rate']:.4f}")

    out += ["", "5. TP 子集: 威胁代码行覆盖 (true_threat=beforeTargetNoNestingLines)",
            "-" * 78,
            f"  {'method':12s}  {'start_in':>8s} {'≥50%catch':>9s} {'full_cov':>8s} {'avg_cov':>7s} {'hit_prec':>8s}     total"]
    for name, r in rows.items():
        m = r["threat_cov"]
        out.append(f"  {name:12s}  "
                   f"{m['start_in_rate']:.4f}   {m['half_caught_rate']:.4f}    "
                   f"{m['full_cover_rate']:.4f}   {m['avg_coverage']:.4f}  {m['avg_hit_precision']:.4f}     {m['total']}")
    out.append("")
    out.append("说明: start_in=真实 try 起始行落入预测覆盖; ≥50%catch=覆盖率≥0.5 且起始行被包;")
    out.append("      full_cov=100% 覆盖; avg_cov=平均覆盖率 (召回向);")
    out.append("      hit_prec=平均命中精度 (= |pred∩true|/|pred|, 精确向). 低 = 圈了很多无关行.")
    out.append("")
    return "\n".join(out)


CLS_INTROS = {
    "sample": (
        "**口径**: 每个样本是一个判定单元。`label==1` = 真实需要补 try-catch；"
        "`changed>0` = 工具判定需要补。混淆矩阵针对 1594 个样本整体累加。\n"
    ),
    "line_nn": (
        "**口径**: 仅在 `label==1` 样本上累加，单位 = 代码行。\n"
        "* 真正例行集合 `true_set` = `beforeTargetNoNestingLines` (真实需要被 try 包住的非嵌套行)。\n"
        "* 预测行集合 `pred_set` = `resultBeforeTargetNoNestingLines`。\n"
        "* TN 用 `方法总行数 - TP - FP - FN` 估算 (整方法所有行都是潜在的"
        "wrap 目标，与 result_manager.ipynb 一致)。\n"
    ),
    "line_range": (
        "**口径**: 同上但用 `[start, end)` 区间。\n"
        "* `true_set` = `[beforeTargetStartLine, beforeTargetEndLine)` 区间内所有行。\n"
        "* `pred_set` = `[resultBeforeTargetStartLine, resultBeforeTargetEndLine)`。\n"
        "* 相比家族 2 (NoNestingLines 集合) 更宽松——把整段 try 内的所有行都算威胁行，"
        "包含嵌套结构带进来的中间行。\n"
    ),
}

METRIC_DEFS = (
    "* **P (Precision, 精确率)** = TP / (TP + FP)，工具说"
    "\"需要补 try-catch\" 时多大比例是对的。\n"
    "* **R (Recall, 召回率)** = TP / (TP + FN)，真实需要补的样本里被工具找出来的比例。\n"
    "* **F1** = P 与 R 的调和均值，单一综合分。\n"
    "* **FPR (False Positive Rate, 误报率)** = FP / (FP + TN)，本不需要 try-catch "
    "却被误判需要的比例。\n"
    "* **FNR (False Negative Rate, 漏报率)** = FN / (TP + FN) = 1 − R，漏掉真正需要"
    "补的比例。\n"
    "* **Acc (Accuracy, 准确率)** = (TP + TN) / 总数，整体判对率（在类不均衡时"
    "意义有限，仅作参考）。\n"
)


def _classification_block_md(title, intro, rows, key):
    out = [f"## {title}\n", intro,
           "| method | P | R | F1 | FPR | FNR | Acc | TP | FP | FN | TN |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, r in rows.items():
        m = r[key]
        out.append(f"| {name} | {m['P']:.4f} | {m['R']:.4f} | {m['F1']:.4f} | "
                   f"{m['FPR']:.4f} | {m['FNR']:.4f} | {m['Acc']:.4f} | "
                   f"{m['TP']} | {m['FP']} | {m['FN']} | {m['TN']} |")
    out.append("")
    return out


def render_markdown(rows):
    out = [
        "# Baseline 对比报告\n",
        "在 1594 样本的 OOD 测试集上对比各 baseline。所有方法的输出 JSON 都通过 "
        "`java-scanner` 的 `RefillFromExternal` 由 `methodResult` 反推 result* "
        "字段，schema 相同，可直接横向比较。\n",
        "## 指标符号约定\n", METRIC_DEFS,
    ]

    for key in ("sample", "line_nn", "line_range"):
        out += _classification_block_md(CLS_TITLES[key], CLS_INTROS[key], rows, key)

    out += [
        "## 4a. TP 子集: 异常类型 — 至少一类命中\n",
        "**口径**: 只看 TP 样本 (`label==1` 且 `changed>0`)。`resultExceptionTypes` 中的 "
        "`Foo|Bar` 形式按 `|` 拆开后并入集合，再与 ground-truth `exceptionTypes` 求交。\n"
        "* **matched** = TP 样本中"
        "\"预测的异常类型与真值至少有一个交集\"的个数。\n"
        "* **total** = TP 样本数。\n"
        "* **hit_rate** = matched / total，即\"猜对一种就算赢\"的占比。\n",
        "| method | matched | total | hit_rate |",
        "|---|---|---|---|",
    ]
    for name, r in rows.items():
        m = r["type_in_tp"]
        out.append(f"| {name} | {m['matched']} | {m['total']} | {m['rate']:.4f} |")
    out.append("")

    out += [
        "## 4b. TP 子集: 异常类型 — 集合级 micro P/R/F1\n",
        "**口径**: TP 样本上把异常类型视作多标签多分类。跨样本累加 (true ∩ pred), "
        "(pred − true), (true − pred) 作 micro 平均。比 4a 更严格——同一样本"
        "里漏掉的、多猜的都会扣分。\n"
        "* **P** = ∑|true∩pred| / ∑|pred|; **R** = ∑|true∩pred| / ∑|true|; "
        "**F1** = 调和均值。\n"
        "* **TP/FP/FN** 在异常类型粒度累加 (一个样本可能贡献多个类型)。\n",
        "| method | P | R | F1 | TP | FP | FN | samples |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for name, r in rows.items():
        m = r["type_set_prf"]
        out.append(f"| {name} | {m['P']:.4f} | {m['R']:.4f} | {m['F1']:.4f} | "
                   f"{m['TP']} | {m['FP']} | {m['FN']} | {m['total']} |")
    out.append("")

    out += [
        "## 5. TP 子集: 威胁代码行覆盖\n",
        "**口径**: 只看 TP 样本。把真实威胁行 (`beforeTargetNoNestingLines`) "
        "与预测覆盖行 (`[resultStart, resultEnd) ∪ resultBeforeTargetNoNestingLines`) "
        "比较，回答\"工具有没有真的把会出异常的语句包进 try\"。\n"
        "* **start_in_rate** = 真实 try 起始行 (`beforeTargetStartLine`) "
        "落入预测覆盖的样本比例。\n"
        "* **≥50% catch** = 起始行被包 *且* 覆盖率 ≥ 0.5 的样本比例，核心\"catch 住\"指标。\n"
        "* **full_cover** = 100% 覆盖真实威胁行的样本比例。\n"
        "* **avg_coverage** (召回向) = 所有 TP 样本的 `命中行数 / 真实威胁行数` 平均值。\n"
        "* **avg_hit_precision** (精确向) = 所有 TP 样本的 `|pred ∩ true| / |pred|` "
        "平均值，即\"工具圈的每一行里有多少是真的威胁行\"。**低 = 圈了大量无关行**，"
        "用来揭穿\"圈整个方法刷威胁覆盖\"的退化策略。与 avg_coverage 配合读："
        "高 avg_coverage + 低 avg_hit_precision = 过覆盖；两者都高才是精准定位。\n",
        "| method | start_in_rate | ≥50% catch | full_cover | avg_coverage | avg_hit_precision | total |",
        "|---|---|---|---|---|---|---|",
    ]
    for name, r in rows.items():
        m = r["threat_cov"]
        out.append(f"| {name} | {m['start_in_rate']:.4f} | "
                   f"{m['half_caught_rate']:.4f} | {m['full_cover_rate']:.4f} | "
                   f"{m['avg_coverage']:.4f} | {m['avg_hit_precision']:.4f} | "
                   f"{m['total']} |")
    return "\n".join(out)


# ---------- main ------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--method", action="append", default=[],
                    help="<name>:<path> 形式，重复使用以加多个；指定后会覆盖默认 METHODS")
    ap.add_argument("--only", help="逗号分隔的 method 名，仅跑这些")
    ap.add_argument("--md", help="把对比表以 Markdown 形式写到该文件")
    args = ap.parse_args()

    if args.method:
        methods = {}
        for spec in args.method:
            if ":" not in spec:
                raise SystemExit(f"--method 需要 name:path 形式: {spec}")
            name, path = spec.split(":", 1)
            methods[name] = path
    else:
        methods = dict(METHODS)

    if args.only:
        wanted = {s.strip() for s in args.only.split(",")}
        methods = {k: v for k, v in methods.items() if k in wanted}
        if not methods:
            raise SystemExit(f"--only 过滤后没有方法剩下; 可选: {list(METHODS)}")

    print(f"对比方法: {list(methods.keys())}\n")
    rows = {}
    for name, path in methods.items():
        try:
            data = load(path)
        except FileNotFoundError:
            print(f"[!] {name}: 文件不存在 -> {path}")
            continue
        rows[name] = dict(
            n=len(data),
            sample=m_sample(data),
            line_nn=m_line_no_nesting(data),
            line_range=m_line_range(data),
            type_in_tp=m_type_hit_in_tp(data),
            type_set_prf=m_type_set_prf(data),
            threat_cov=m_threat_coverage_in_tp(data),
        )
        print(f"  loaded {name}: {len(data)} entries  ({path})")

    if not rows:
        raise SystemExit("没有可用的方法")

    print()
    print(render_text(rows))

    if args.md:
        Path(args.md).write_text(render_markdown(rows), encoding="utf-8")
        print(f"\nmarkdown 写入: {args.md}")


if __name__ == "__main__":
    main()

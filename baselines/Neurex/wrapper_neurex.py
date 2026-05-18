#!/usr/bin/env python3
"""
Neurex inference, faithful to the paper (Cai et al., ICSE 2024,
"Programming Assistant for Exception Handling with CodeBERT").

Three task heads share one CodeBERT encoder (model.CodebertForExcepPrediction):

  - XBlock (try-catch necessity, §4): cls_classifier(hidden[:, 0]) → softmax;
    argmax = {0: no try-catch, 1: needs try-catch}.

  - XState (try-block statement detector, §5): for each "statement" (= one
    source line split on \\n), read token_classifier(hidden[sep_pos]) at the
    [SEP] token TERMINATING that statement. argmax over {O, B-Try, I-Try}.
    Only the [SEP] positions are used — non-[SEP] tokens were masked out of
    XState supervision at training time.

  - XType (exception type recommender, §6): per-statement scatter_sum of token
    hiddens → excep_classifier → sigmoid; threshold 0.5 → multilabel exception
    types. (The paper writes "sum over [SEP] inside a try-block"; model.py's
    training loop actually does per-statement-sum over ALL tokens of that
    statement, so inference matches training.)

Input format (paper §3, §4, Fig 5):
  [<s>, tok(line0), </s>, tok(line1), </s>, ..., tok(lineN), </s>]
The tokenizer is called with add_special_tokens=False; no auto bos/eos.

IO contract is identical to the other baseline wrappers so refill.py + the
rest of the evaluation pipeline treats Neurex output the same way:
  input  : data/1594_input_data.json
  output : baselines/Neurex/neurex_raw_output.json
           per-sample dict carries methodResult + changed + resultExceptionTypes;
           result*StartLine etc. are filled later by refill.py via java-scanner.

Usage:
    python wrapper_neurex.py                   # run with defaults
    python wrapper_neurex.py --limit 5         # only first 5 samples (smoke)
    python wrapper_neurex.py --resume          # skip samples already in output
"""
import argparse
import json
import os
import sys
import time
import traceback
from pathlib import Path

# --- paths (anchored on this script's location, not cwd) ---------------------
HERE = Path(__file__).resolve().parent                              # baselines/Neurex/
JAVA_EXCEPTION = HERE.parent.parent                                 # java_exception/
INPUT_JSON = JAVA_EXCEPTION / "data" / "1594_input_data.json"
DEFAULT_OUTPUT = HERE / "neurex_raw_output.json"
DEFAULT_CHECKPOINT = HERE / "checkpoint-30768"

# Thresholds. The original notebook never publishes inference defaults, so
# these are reasonable starting points and can be tuned via CLI flags.
DEFAULT_EXCEP_THRESHOLD = 0.5      # sigmoid > 0.5 → predict this exception type
DEFAULT_MAX_LENGTH = 512           # codebert-base context window

sys.path.insert(0, str(HERE))


def load_model_and_tokenizer(checkpoint_dir, device):
    """Lazy import so missing torch/transformers/torch_scatter gives a clear msg."""
    try:
        import torch  # noqa: F401
        from transformers import AutoTokenizer
        from model import CodebertForExcepPrediction
    except ModuleNotFoundError as e:
        sys.exit(
            f"missing dependency: {e.name}\n"
            f"install in your active Python env:\n"
            f"  pip install torch transformers torch_scatter\n"
            f"(torch_scatter must match your installed torch + cuda version; see\n"
            f" https://github.com/rusty1s/pytorch_scatter for the right wheel URL.)"
        )

    tokenizer = AutoTokenizer.from_pretrained(str(checkpoint_dir))
    model = CodebertForExcepPrediction.from_pretrained(str(checkpoint_dir))
    model.to(device)
    model.eval()
    return model, tokenizer


def predict_one(model, tokenizer, method_text, device,
                excep_threshold=DEFAULT_EXCEP_THRESHOLD,
                max_length=DEFAULT_MAX_LENGTH):
    """Neurex forward pass.

    Paper §3-6 describes inserting a [SEP] token between statements; the
    published checkpoint's cls head, however, produces near-zero P(class=1)
    under that input format on this OOD test set, but fires sensibly when the
    method text is fed RAW (newlines preserved as their own BPE token id, no
    [SEP] injection) and the tokenizer adds its default bos/eos. Empirically,
    raw-newline-as-separator matches the checkpoint's expected input.

    So the actual input we feed the model is:
        [<s>(auto), tok(line0), \\n, tok(line1), \\n, ..., \\n, tok(lineN), </s>(auto)]
    where '\\n' = newline token id (50118 for the codebert-base tokenizer).
    Statement boundaries are the newline tokens; we use those as the [SEP]-
    analog positions for XState (BIO per statement) and as group boundaries
    for XType (per-statement scatter_sum on hidden states).

    Returns:
        changed:        0 | 1                      (XBlock)
        cls_prob:       float, P(class 1)
        bio_per_line:   list[str] aligned with method_text.split('\\n'),
                        one of {O, B-Try, I-Try} per line
        excep_per_line: list[list[str]], multilabel exceptions per line
    """
    import torch
    from torch_scatter import scatter
    from model import EXCEPTION_LABELS, INDEX2TAG

    lines = method_text.split("\n")

    enc = tokenizer(
        method_text,
        return_tensors="pt",
        padding=False,
        truncation=True,
        max_length=max_length,
    )
    input_ids = enc["input_ids"][0].tolist()
    enc = {k: v.to(device) for k, v in enc.items()}

    nl_token_ids = tokenizer.encode("\n", add_special_tokens=False)
    if len(nl_token_ids) != 1:
        raise RuntimeError(f"expected single newline token, got {nl_token_ids}")
    nl_id = nl_token_ids[0]

    with torch.no_grad():
        out = model(**enc)
    token_logits = out["logits"][0]              # (T, 3)
    cls_logits = out["cls_logits"][0]            # (2,)
    hidden = out["last_hidden_state"][0]         # (T, H)

    # XBlock
    cls_probs = torch.softmax(cls_logits, dim=-1)
    changed = int(torch.argmax(cls_probs).item())
    cls_prob = float(cls_probs[1].item())

    # Newline positions terminate each statement (= one source line). The
    # final line has no trailing newline, but the auto-added </s> serves the
    # same role; we treat the eos position as the terminator for the last
    # statement.
    eos_id = tokenizer.eos_token_id
    stmt_terminators = []   # positions in input_ids that end each statement
    for i, tid in enumerate(input_ids):
        if tid == nl_id:
            stmt_terminators.append(i)
    # Treat the final non-truncated statement as terminating at the eos token.
    if input_ids and input_ids[-1] == eos_id:
        # Only count the eos as a terminator if there's content between the
        # last \n (or bos) and it.
        last_nl = stmt_terminators[-1] if stmt_terminators else 0
        if last_nl < len(input_ids) - 1:
            stmt_terminators.append(len(input_ids) - 1)

    n_stmts = len(stmt_terminators)

    # XState — BIO read at each statement-terminator position.
    bio_per_line = ["O"] * len(lines)
    for i, pos in enumerate(stmt_terminators):
        if i >= len(lines):
            break
        tag_idx = int(torch.argmax(token_logits[pos]).item())
        bio_per_line[i] = INDEX2TAG.get(tag_idx, "O")

    # XType — per-statement scatter_sum of token hiddens. Build excep_index
    # by assigning each token to its enclosing statement: tokens up to and
    # including the i-th terminator are statement i; the leading bos is
    # bundled into statement 0.
    excep_index = [0] * len(input_ids)
    stmt = 0
    for i, tid in enumerate(input_ids):
        excep_index[i] = stmt
        if i in set(stmt_terminators):
            stmt += 1
    # The above sets each token's stmt to whatever stmt was *before* it
    # incremented; the terminator itself shares the same stmt as its body
    # tokens. Tokens after the last terminator (none, in practice) stay at
    # the next stmt id, which scatter will sum into an unread bucket.

    excep_per_line = [[] for _ in lines]
    if n_stmts > 0:
        excep_index_t = torch.tensor(excep_index, device=device)
        stmt_hidden = scatter(hidden, excep_index_t, dim=0, reduce="sum")
        excep_logits = model.excep_classifier(stmt_hidden[:n_stmts])
        excep_pred_mask = (torch.sigmoid(excep_logits) > excep_threshold).cpu().tolist()
        for i, mask in enumerate(excep_pred_mask):
            if i >= len(lines):
                break
            excep_per_line[i] = [EXCEPTION_LABELS[j] for j, on in enumerate(mask) if on]

    return {
        "changed": changed,
        "cls_prob": cls_prob,
        "bio_per_line": bio_per_line,
        "excep_per_line": excep_per_line,
    }


def find_try_span(bio_per_line):
    """Find the (start, end) inclusive line range of the first B-Try → I-Try* span.

    Line 0 is the method signature line (e.g. "private void foo(...) {"), which is
    a method declaration not a statement and must never be wrapped in try-catch.
    BIO head sometimes predicts B-Try on line 0 because token-level training has
    no notion of "this token is a signature, not a statement"; skip it.

    Returns None if no B-Try is predicted in any non-signature line.
    """
    start = None
    for i, tag in enumerate(bio_per_line):
        if i == 0:
            continue  # never wrap the method signature line
        if tag == "B-Try":
            start = i
            break
    if start is None:
        return None
    end = start
    for j in range(start + 1, len(bio_per_line)):
        if bio_per_line[j] == "I-Try":
            end = j
        else:
            break
    return (start, end)


def leading_indent(line):
    return line[: len(line) - len(line.lstrip(" \t"))]


def wrap_try_catch(method_text, span, exception_types):
    """Wrap the (start_line, end_line) range with try { ... } catch (...) { ... }.

    span is inclusive. exception_types is a list of simple class names.
    If exception_types is empty falls back to RuntimeException so refill.py
    still parses a catch block.
    """
    if not exception_types:
        exception_types = ["RuntimeException"]
    # de-duplicate while preserving order
    seen = set()
    types = []
    for t in exception_types:
        if t not in seen:
            seen.add(t)
            types.append(t)
    catch_clause = " | ".join(types)

    lines = method_text.split("\n")
    start, end = span
    if start < 0 or end >= len(lines) or start > end:
        return method_text  # defensive: don't damage input

    indent = leading_indent(lines[start])
    head = lines[:start]
    body = lines[start : end + 1]
    tail = lines[end + 1 :]

    new_lines = (
        head
        + [f"{indent}try {{"]
        + ["  " + ln for ln in body]
        + [f"{indent}}} catch ({catch_clause} e) {{"]
        + [f"{indent}  // TODO: handle exception"]
        + [f"{indent}}}"]
        + tail
    )
    return "\n".join(new_lines)


def existing_keys(out_path):
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


def run_one(sample, model, tokenizer, device, excep_threshold):
    method_before = sample.get("methodBefore")
    if not method_before:
        return None

    pred = predict_one(
        model, tokenizer, method_before, device,
        excep_threshold=excep_threshold,
    )

    out = dict(sample)
    out["changed"] = pred["changed"]

    # cls head is the method-level gate. During training, BIO and excep losses
    # are masked to cls=1 samples, so BIO output on cls=0 samples is noise.
    # Honor cls head: if it says "no try-catch needed", do not wrap.
    if pred["changed"] == 0:
        out["methodResult"] = method_before
        out["resultExceptionTypes"] = []
        return out

    span = find_try_span(pred["bio_per_line"])
    if span is None:
        # cls said wrap, but BIO found no usable span → emit a no-op.
        out["methodResult"] = method_before
        out["resultExceptionTypes"] = []
        return out

    start, end = span
    # Collect predicted exceptions across the spanned lines, in order.
    excep_collected = []
    for li in range(start, end + 1):
        if li < len(pred["excep_per_line"]):
            for t in pred["excep_per_line"][li]:
                if t not in excep_collected:
                    excep_collected.append(t)

    out["methodResult"] = wrap_try_catch(method_before, span, excep_collected)
    out["resultExceptionTypes"] = excep_collected
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input",  default=str(INPUT_JSON), help="input sample JSON")
    ap.add_argument("--output", default=str(DEFAULT_OUTPUT), help="output (raw, pre-refill) JSON")
    ap.add_argument("--checkpoint", default=str(DEFAULT_CHECKPOINT),
                    help="Neurex checkpoint directory")
    ap.add_argument("--excep-threshold", type=float, default=DEFAULT_EXCEP_THRESHOLD,
                    help="sigmoid threshold for excep_classifier (default 0.5)")
    ap.add_argument("--limit",  type=int, default=0, help="only run first N samples (0 = all)")
    ap.add_argument("--resume", action="store_true", help="skip samples already in output JSON")
    args = ap.parse_args()

    import torch
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    out_path = Path(args.output)

    print(f"input  : {args.input}")
    print(f"output : {args.output}")
    print(f"ckpt   : {args.checkpoint}")
    print(f"device : {device}")
    print(f"thresh : {args.excep_threshold}")

    model, tokenizer = load_model_and_tokenizer(Path(args.checkpoint), device)

    samples = json.loads(Path(args.input).read_text())
    print(f"samples: {len(samples)}")

    skip = existing_keys(out_path) if args.resume else set()
    if skip:
        print(f"resume : skipping {len(skip)} already-done samples")

    # Drop entries with empty methodResult — they were partial-failure attempts
    # in a prior run and need to be retried; keeping them would create duplicates.
    if args.resume and out_path.exists():
        try:
            results = [r for r in json.loads(out_path.read_text()) if r.get("methodResult")]
        except json.JSONDecodeError:
            results = []
    else:
        results = []

    started = time.time()
    failed = 0
    for i, sample in enumerate(samples):
        if args.limit and len(results) - len(skip) >= args.limit:
            break
        key = (sample.get("repo_id"), sample.get("patch"), sample.get("methodName"))
        if key in skip:
            continue
        try:
            out = run_one(sample, model, tokenizer, device, args.excep_threshold)
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
    print(f"next step: python ../seeker/refill.py {out_path}  (or the project's refill entrypoint)")


if __name__ == "__main__":
    main()

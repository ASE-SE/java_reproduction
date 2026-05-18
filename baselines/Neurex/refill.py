#!/usr/bin/env python3
"""
Refill the result* fields of Neurex output JSON by delegating to java-scanner's
MethodDiffAnalyzer (org.ExperimentExecutor.RefillFromExternal).

Twin of baselines/seeker/refill.py, but defaults to:
  input  = baselines/Neurex/neurex_raw_output.json
  output = baselines/Neurex/neurex_output.json

so Neurex artefacts stay under baselines/Neurex/ instead of leaking into seeker/.
Both baselines go through the exact same MethodDiffAnalyzer extractor — that's
how their metrics stay comparable.

Note: unlike seeker/refill.py, the output filename does NOT include
EXPERIMENT_MARK. Neurex uses a fixed CodeBERT checkpoint, not an LLM, so the
mark (a LLM-model identifier) doesn't apply. If you want to keep multiple
Neurex runs side-by-side, pass an explicit output path.

Usage:
    python refill.py [<raw_input.json>] [<refilled_output.json>]

Requires java-scanner to be already compiled:
    cd ../../java-scanner && mvn -DskipTests package dependency:copy-dependencies
"""
import argparse
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent              # baselines/Neurex/
JAVA_EXCEPTION = HERE.parent.parent                 # project root
JAVA_SCANNER = JAVA_EXCEPTION / "java-scanner"
CLASSPATH = f"{JAVA_SCANNER / 'target' / 'classes'}:{JAVA_SCANNER / 'target' / 'dependency' / '*'}"
ENTRY_CLASS = "org.ExperimentExecutor.RefillFromExternal"


def check_compiled():
    classes = JAVA_SCANNER / "target" / "classes" / "org" / "ExperimentExecutor" / "RefillFromExternal.class"
    deps = JAVA_SCANNER / "target" / "dependency"
    if not classes.exists() or not deps.exists() or not any(deps.iterdir()):
        sys.exit(
            f"java-scanner is not compiled (or RefillFromExternal not built yet).\n"
            f"run:\n"
            f"  cd {JAVA_SCANNER}\n"
            f"  mvn -DskipTests package dependency:copy-dependencies"
        )


def main():
    default_in = HERE / "neurex_raw_output.json"
    default_out = HERE / "neurex_output.json"

    ap = argparse.ArgumentParser()
    ap.add_argument("input",  nargs="?", default=str(default_in))
    ap.add_argument("output", nargs="?", default=str(default_out))
    args = ap.parse_args()

    in_path = Path(args.input).resolve()
    out_path = Path(args.output).resolve()

    if not in_path.exists():
        sys.exit(f"input not found: {in_path}\nrun wrapper_neurex.py first.")
    check_compiled()

    print(f"refill: {in_path}")
    print(f"     -> {out_path}")
    print(f"     using java-scanner classpath at {JAVA_SCANNER}")

    cmd = [
        "java", "-cp", CLASSPATH,
        ENTRY_CLASS,
        str(in_path), str(out_path),
    ]
    # cwd MUST be java-scanner/ so ExampleHandler/Main can read config.properties via relative path.
    subprocess.run(cmd, cwd=str(JAVA_SCANNER), check=True)
    print(f"\ndone. {out_path}")


if __name__ == "__main__":
    main()

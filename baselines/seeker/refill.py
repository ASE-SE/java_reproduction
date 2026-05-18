#!/usr/bin/env python3
"""
Fill in the result* fields (resultExceptionTypes, resultCatchBlocks,
resultBefore/AfterTargetStartLine, changed, …) of a Seeker output JSON by
delegating to java-scanner's MethodDiffAnalyzer via the
org.ExperimentExecutor.RefillFromExternal entry point.

This keeps Seeker's outputs comparable to the main pipeline's outputs because
both go through the same MethodDiffAnalyzer extractor.

Usage:
    python refill.py [<raw_input.json>] [<refilled_output.json>]

If both args omitted: reads seeker_raw_output.json and writes
seeker_<MARK>_output.json, where MARK is read from java-scanner/config.properties.

Requires java-scanner to be already compiled:
    cd ../../java-scanner && mvn -DskipTests package dependency:copy-dependencies
"""
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
JAVA_EXCEPTION = HERE.parent.parent
JAVA_SCANNER = JAVA_EXCEPTION / "java-scanner"
CONFIG_PROPERTIES = JAVA_SCANNER / "config.properties"
CLASSPATH = f"{JAVA_SCANNER / 'target' / 'classes'}:{JAVA_SCANNER / 'target' / 'dependency' / '*'}"
ENTRY_CLASS = "org.ExperimentExecutor.RefillFromExternal"


def read_mark():
    if not CONFIG_PROPERTIES.exists():
        return "unknown"
    for line in CONFIG_PROPERTIES.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^EXPERIMENT_MARK\s*=\s*(.+?)\s*$", line)
        if m and not line.lstrip().startswith("#"):
            return m.group(1)
    return "unknown"


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
    mark = read_mark()
    default_in = HERE / f"seeker_{mark}_raw_output.json"
    default_out = HERE / f"seeker_{mark}_output.json"

    ap = argparse.ArgumentParser()
    ap.add_argument("input",  nargs="?", default=str(default_in))
    ap.add_argument("output", nargs="?", default=str(default_out))
    args = ap.parse_args()

    in_path = Path(args.input).resolve()
    out_path = Path(args.output).resolve()

    if not in_path.exists():
        sys.exit(f"input not found: {in_path}\nrun wrapper_seeker.py first.")
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

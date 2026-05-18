#!/usr/bin/env python3
"""
Twin of baselines/seeker/refill.py and baselines/Neurex/refill.py — same logic,
just nexgen-flavored defaults.

Defaults:
  input  = baselines/nexgen/nexgen_raw_output.json    (or retrain_raw_output.json)
  output = baselines/nexgen/nexgen_output.json        (or retrain_output.json)

Run twice, once per config:
  python 08_refill.py nexgen_raw_output.json  nexgen_output.json
  python 08_refill.py retrain_raw_output.json retrain_output.json

Requires java-scanner already compiled (see top-level README / refill.py twins).
"""
import argparse
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent           # baselines/nexgen/scripts
NEXGEN_DIR = HERE.parent                          # baselines/nexgen
JAVA_EXCEPTION = NEXGEN_DIR.parent.parent         # project root
JAVA_SCANNER = JAVA_EXCEPTION / "java-scanner"
CLASSPATH = f"{JAVA_SCANNER / 'target' / 'classes'}:{JAVA_SCANNER / 'target' / 'dependency' / '*'}"
ENTRY_CLASS = "org.ExperimentExecutor.RefillFromExternal"


def check_compiled():
    classes = JAVA_SCANNER / "target" / "classes" / "org" / "ExperimentExecutor" / "RefillFromExternal.class"
    deps = JAVA_SCANNER / "target" / "dependency"
    if not classes.exists() or not deps.exists() or not any(deps.iterdir()):
        sys.exit(
            f"java-scanner not compiled. run:\n"
            f"  cd {JAVA_SCANNER}\n"
            f"  mvn -DskipTests package dependency:copy-dependencies"
        )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    args = ap.parse_args()
    in_path = Path(args.input).resolve()
    out_path = Path(args.output).resolve()
    if not in_path.exists():
        sys.exit(f"input not found: {in_path}")
    check_compiled()

    print(f"refill: {in_path}  ->  {out_path}")
    print(f"  classpath: {JAVA_SCANNER}")
    subprocess.run(
        ["java", "-cp", CLASSPATH, ENTRY_CLASS, str(in_path), str(out_path)],
        cwd=str(JAVA_SCANNER), check=True,
    )
    print(f"done. {out_path}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Run a test graph by name, then aggregate the reports.

Every `testGraph("X") { ... }` in build.gradle.kts registers a Gradle
task named `X`. This script invokes `./gradlew X` then runs
`validationReport` to roll envelopes into summary.json.

Usage:
    run.py <graph-name>
        e.g. run.py smoke
"""
from __future__ import annotations

import argparse
import sys

from _common import run_gradle


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "graph",
        help="Test graph name (also the Gradle task name). "
             "List available graphs with `./gradlew validationListGraphs`.",
    )
    args = parser.parse_args()

    code = run_gradle(["--console=plain", args.graph])
    if code != 0:
        return code
    return run_gradle(["--console=plain", "-q", "validationReport"])


if __name__ == "__main__":
    sys.exit(main())

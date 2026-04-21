"""Shared helpers for the test-graph skill scripts.

Two roots to keep straight:

1. `skill_root()` — the root of THIS repository (where SKILL.md lives).
   Used by scaffold to locate `project_sdk_sources/` and by
   `new-*-node.py` to locate the templates.

2. `target_project_root()` — the scaffolded test_graph project the user
   is currently operating on. Detected by walking up from cwd looking
   for `settings.gradle.kts`. Used by `discover.py`, `run.py`, and the
   `new-*-node.py` scripts when writing new files into `sources/`.

The two-root split lets one checked-out skill serve many scaffolded
projects without path guessing or per-invocation flags.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

VALID_KINDS = {"testbed", "fixture", "action", "assertion", "evidence", "report"}

# Dotted lowercase segments: app.running, checkout.smoke, user.seeded.v2, ...
_NODE_ID_RE = re.compile(r"^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$")


def skill_root() -> Path:
    """Root of the test-graph skill repo (where SKILL.md lives).

    Layout: <skill-root>/scripts/_common.py
    parents: [0]=scripts/  [1]=<skill-root>
    """
    return Path(__file__).resolve().parents[1]


def project_sdk_sources() -> Path:
    """Template project directory that gets copied on scaffold."""
    return skill_root() / "project_sdk_sources"


def templates_dir() -> Path:
    return skill_root() / "templates"


def target_project_root() -> Path:
    """Walk up from cwd to find a scaffolded test_graph project.

    The marker is `settings.gradle.kts` at the project root. Errors with
    a helpful message if run outside a test_graph project.
    """
    cur = Path.cwd().resolve()
    while True:
        if (cur / "settings.gradle.kts").is_file():
            return cur
        if cur.parent == cur:
            sys.exit(
                "error: not inside a test_graph project (no settings.gradle.kts "
                "found walking up from cwd).\n"
                "  Scaffold one first with:  "
                f"{skill_root() / 'scripts' / 'scaffold.py'} <repo-root>\n"
                "  Then cd into <repo-root>/test_graph before re-running."
            )
        cur = cur.parent


def target_sources_dir() -> Path:
    """`sources/` inside the active scaffolded project."""
    return target_project_root() / "sources"


def validate_node_id(node_id: str) -> None:
    if not _NODE_ID_RE.match(node_id):
        sys.exit(
            f"error: node id must be dotted lowercase segments (got {node_id!r}); "
            f"e.g. 'checkout.smoke'"
        )


def validate_kind(kind: str) -> None:
    if kind not in VALID_KINDS:
        sys.exit(
            f"error: invalid kind {kind!r}; expected one of {sorted(VALID_KINDS)}"
        )


def class_name_from_id(node_id: str) -> str:
    """Camel-case a dotted node id into a Java class name (app.running -> AppRunning)."""
    return "".join(part[:1].upper() + part[1:] for part in node_id.split("."))


def snake_name_from_id(node_id: str) -> str:
    """Snake-case a dotted node id into a Python module name (app.running -> app_running)."""
    return node_id.replace(".", "_")


def render_template(template_path: Path, replacements: dict[str, str]) -> str:
    text = template_path.read_text()
    for key, value in replacements.items():
        text = text.replace(key, value)
    return text


def run_gradle(args: list[str]) -> int:
    """Invoke gradlew from the active scaffolded project.

    Inherits stdio so the user sees output live.
    """
    root = target_project_root()
    gradlew = root / "gradlew"
    cmd = [str(gradlew)] + args if gradlew.exists() else ["gradle"] + args
    env = os.environ.copy()
    proc = subprocess.run(cmd, cwd=root, env=env)
    return proc.returncode

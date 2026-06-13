from __future__ import annotations

import json
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def provisioning_state_root(report_dir: Path) -> Path:
    return report_dir.parent.parent / "testgraph-provisioning-state"


def provisioned_marker(report_dir: Path, environment_id: str) -> Path:
    return provisioning_state_root(report_dir) / "provisioned" / f"{environment_id}.json"


def reset_markers(report_dir: Path, environment_id: str, run_id: str) -> list[Path]:
    reset_dir = provisioning_state_root(report_dir) / "reset"
    if not reset_dir.is_dir():
        return []
    return sorted(reset_dir.glob(f"{environment_id}__{run_id}__*.json"))


def read_json(path: Path) -> dict:
    return json.loads(path.read_text()) if path.is_file() else {}


def ticket_plan_text() -> str:
    return (REPO_ROOT / "specs/desired_program_model/ticket_plan.yaml").read_text()

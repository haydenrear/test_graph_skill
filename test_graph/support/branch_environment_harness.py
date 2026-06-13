from __future__ import annotations

import json
import shutil
import subprocess
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


def environment_repository_source_dir() -> Path:
    return REPO_ROOT / "test_graph" / "environment-repository-source"


def generated_environment_repository_dir(report_dir: Path) -> Path:
    return report_dir / "generated-environment-repository"


def copy_environment_repository_source(report_dir: Path) -> Path:
    source = environment_repository_source_dir()
    destination = generated_environment_repository_dir(report_dir)
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination)
    return destination


def git(repo: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(repo), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"git {' '.join(args)} failed with exit {completed.returncode}: {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


def init_environment_repository(repo: Path) -> str:
    git(repo, "init")
    git(repo, "config", "user.email", "test-graph@example.invalid")
    git(repo, "config", "user.name", "Test Graph")
    git(repo, "add", ".")
    git(repo, "commit", "-m", "Initial local preview environment")
    return git(repo, "rev-parse", "HEAD")


def local_preview_output_keys(repo: Path) -> set[str]:
    outputs = repo / "templates" / "local-preview" / "outputs.tf"
    text = outputs.read_text(encoding="utf-8")
    return {
        key
        for key in ("EnvironmentId", "KUBECONFIG", "KUBECONTEXT")
        if f'output "{key}"' in text
    }

# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Self-validation node for the test-graph resume flow."""
from __future__ import annotations

import re
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Mapping

from testgraphsdk import NodeResult, NodeSpec, node


REPO_ROOT = Path(__file__).resolve().parents[2]
PROJECT_GRAPH_ROOT = REPO_ROOT / "project_sdk_sources"
RUN_ID_RE = re.compile(r"testGraph '([^']+)' run=([0-9]{8}-[0-9]{6})")


SPEC = (
    NodeSpec("self.rerun.build.flow")
    .kind("assertion")
    .tags("self", "rerun", "resume")
    .timeout("10m")
    .side_effects("process:gradle", "net:local")
    .output("smokeRunId", "string")
    .output("rerunSmokeUvRunId", "string")
    .output("rerunSmokeJavaRunId", "string")
)


class _FixtureHandler(BaseHTTPRequestHandler):
    def do_HEAD(self) -> None:
        if self.path == "/login":
            self.send_response(302)
            self.send_header("Location", "/dashboard")
        else:
            self.send_response(200)
        self.end_headers()

    def do_GET(self) -> None:
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt: str, *args: object) -> None:
        return


def _run(label: str, args: list[str], timeout: int = 900) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        timeout=timeout,
    )


def _summarize(label: str, completed: subprocess.CompletedProcess[str]) -> str:
    text = (completed.stdout + "\n" + completed.stderr).strip()
    tail = "\n".join(text.splitlines()[-25:])
    return f"{label} exited {completed.returncode}\n{tail}"


def _extract_run_ids(output: str) -> Mapping[str, str]:
    return {graph: run_id for graph, run_id in RUN_ID_RE.findall(output)}


@node(SPEC)
def main(ctx):
    server = HTTPServer(("127.0.0.1", 8080), _FixtureHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    try:
        run_all = _run(
            "run all project graphs",
            [
                str(REPO_ROOT / "scripts/run.py"),
                "--all",
                "--test-graph-root",
                str(PROJECT_GRAPH_ROOT),
            ],
        )
        output = run_all.stdout + "\n" + run_all.stderr
        run_ids = _extract_run_ids(output)
        smoke_run_id = run_ids.get("smoke", "")
        smoke_build = PROJECT_GRAPH_ROOT / "build/validation-reports" / smoke_run_id

        resume_login = _run(
            "resume login.smoke",
            [
                str(REPO_ROOT / "scripts/run.py"),
                "smoke",
                "--test-graph-root",
                str(PROJECT_GRAPH_ROOT),
                "--resume-from-build",
                str(smoke_build),
                "--resume-from-node",
                "login.smoke",
            ],
        )
        resume_context = _run(
            "resume context.snapshots.present",
            [
                str(REPO_ROOT / "scripts/run.py"),
                "smoke",
                "--test-graph-root",
                str(PROJECT_GRAPH_ROOT),
                "--resume-from-build",
                str(smoke_build),
                "--resume-from-node",
                "context.snapshots.present",
            ],
        )
        resume_disabled = _run(
            "reject rerun.disabled.probe",
            [
                str(REPO_ROOT / "scripts/run.py"),
                "smoke",
                "--test-graph-root",
                str(PROJECT_GRAPH_ROOT),
                "--resume-from-build",
                str(smoke_build),
                "--resume-from-node",
                "rerun.disabled.probe",
            ],
        )

        result = (
            NodeResult.pass_(ctx.node_id)
            .assertion("run_all_project_graphs_passed", run_all.returncode == 0)
            .assertion("smoke_run_id_found", bool(smoke_run_id))
            .assertion("rerun_smoke_uv_run_id_found", bool(run_ids.get("rerunSmokeUv")))
            .assertion("rerun_smoke_java_run_id_found", bool(run_ids.get("rerunSmokeJava")))
            .assertion("smoke_build_dir_exists", smoke_build.is_dir())
            .assertion("resume_login_continued_downstream", resume_login.returncode == 0)
            .assertion("resume_context_final_node_passed", resume_context.returncode == 0)
            .assertion("rerun_false_selection_rejected", resume_disabled.returncode != 0)
            .assertion(
                "rerun_false_rejection_explained",
                "rerun=false" in (resume_disabled.stdout + resume_disabled.stderr),
            )
            .metric("projectGraphCount", len(run_ids))
            .log(_summarize("run_all", run_all))
            .log(_summarize("resume_login", resume_login))
            .log(_summarize("resume_context", resume_context))
            .log(_summarize("resume_disabled", resume_disabled))
            .publish("smokeRunId", smoke_run_id)
            .publish("rerunSmokeUvRunId", run_ids.get("rerunSmokeUv", ""))
            .publish("rerunSmokeJavaRunId", run_ids.get("rerunSmokeJava", ""))
        )
        if smoke_build.is_dir():
            result.artifact("project-smoke-report", str(smoke_build / "report.md"))
        return result
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()

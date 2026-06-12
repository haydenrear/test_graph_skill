from __future__ import annotations

import os
import re
import subprocess
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Iterator, Mapping


REPO_ROOT = Path(__file__).resolve().parents[2]
PROJECT_GRAPH_ROOT = REPO_ROOT / "project_sdk_sources"
RUN_ID_RE = re.compile(r"testGraph '([^']+)' run=([0-9]{8}-[0-9]{6})")


class _ReusableHTTPServer(HTTPServer):
    allow_reuse_address = True


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


@contextmanager
def local_http_fixture() -> Iterator[None]:
    server = _ReusableHTTPServer(("127.0.0.1", 8080), _FixtureHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield
    finally:
        server.shutdown()
        server.server_close()


def run_command(
    label: str,
    args: list[str],
    *,
    env: Mapping[str, str] | None = None,
    timeout: int = 900,
) -> subprocess.CompletedProcess[str]:
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    completed = subprocess.run(
        args,
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        timeout=timeout,
        env=merged_env,
    )
    completed.args = [label, *args]
    return completed


def run_project_graph(
    graph: str,
    extra_args: list[str] | None = None,
    *,
    env: Mapping[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    args = [
        str(REPO_ROOT / "scripts/run.py"),
        graph,
        "--test-graph-root",
        str(PROJECT_GRAPH_ROOT),
    ]
    if extra_args:
        args.extend(extra_args)
    return run_command(graph, args, env=env)


def run_smoke_baseline() -> tuple[subprocess.CompletedProcess[str], str, Path]:
    completed = run_project_graph("smoke")
    run_id = extract_run_ids(completed.stdout + "\n" + completed.stderr).get("smoke", "")
    return completed, run_id, PROJECT_GRAPH_ROOT / "build/validation-reports" / run_id


def extract_run_ids(output: str) -> Mapping[str, str]:
    return {graph: run_id for graph, run_id in RUN_ID_RE.findall(output)}


def output_of(completed: subprocess.CompletedProcess[str]) -> str:
    return completed.stdout + completed.stderr


def summarize(label: str, completed: subprocess.CompletedProcess[str]) -> str:
    text = (completed.stdout + "\n" + completed.stderr).strip()
    tail = "\n".join(text.splitlines()[-25:])
    return f"{label} exited {completed.returncode}\n{tail}"

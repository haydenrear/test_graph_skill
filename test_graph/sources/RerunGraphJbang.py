# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Validate graph resume starting at a JBang node."""
from __future__ import annotations

import sys
from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from rerun_harness import local_http_fixture, output_of, run_project_graph, run_smoke_baseline, summarize


SPEC = (
    NodeSpec("self.rerun.graph.jbang")
    .kind("assertion")
    .tags("self", "rerun", "resume", "jbang")
    .timeout("10m")
    .side_effects("process:gradle", "net:local")
    .output("smokeRunId", "string")
)


@node(SPEC)
def main(ctx):
    with local_http_fixture():
        baseline, run_id, smoke_build = run_smoke_baseline()
        resume = run_project_graph(
            "smoke",
            [
                "--resume-from-build",
                str(smoke_build),
                "--resume-from-node",
                "login.smoke",
            ],
        )

    resume_output = output_of(resume)
    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("baseline_smoke_passed", baseline.returncode == 0)
        .assertion("smoke_run_id_found", bool(run_id))
        .assertion("smoke_build_dir_exists", smoke_build.is_dir())
        .assertion("resume_from_jbang_node_passed", resume.returncode == 0)
        .assertion("selected_node_was_jbang", "  [1/3] login.smoke (jbang)" in resume_output)
        .assertion("resume_continued_downstream", "  [3/3] context.snapshots.present (uv)" in resume_output)
        .log(summarize("baseline_smoke", baseline))
        .log(summarize("resume_login", resume))
        .publish("smokeRunId", run_id)
    )
    if smoke_build.is_dir():
        result.artifact("project-smoke-report", str(smoke_build / "report.md"))
    return result


if __name__ == "__main__":
    main()

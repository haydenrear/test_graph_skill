# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Validate single-node replay for a uv node."""
from __future__ import annotations

import sys
from pathlib import Path

from testgraphsdk import NodeResult, NodeSpec, node

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "support"))
from rerun_harness import local_http_fixture, output_of, run_project_graph, run_smoke_baseline, summarize


SPEC = (
    NodeSpec("self.run.only.uv")
    .kind("assertion")
    .tags("self", "rerun", "run-only", "uv")
    .timeout("10m")
    .side_effects("process:gradle", "net:local")
    .output("smokeRunId", "string")
)


@node(SPEC)
def main(ctx):
    with local_http_fixture():
        baseline, run_id, smoke_build = run_smoke_baseline()
        run_only = run_project_graph(
            "smoke",
            [
                "--resume-from-build",
                str(smoke_build),
                "--run-only-node",
                "user.seeded",
            ],
        )
        rerun_false = run_project_graph(
            "smoke",
            [
                "--resume-from-build",
                str(smoke_build),
                "--run-only-node",
                "rerun.disabled.probe",
            ],
        )

    run_only_output = output_of(run_only)
    rerun_false_output = output_of(rerun_false)
    result = (
        NodeResult.pass_(ctx.node_id)
        .assertion("baseline_smoke_passed", baseline.returncode == 0)
        .assertion("smoke_run_id_found", bool(run_id))
        .assertion("smoke_build_dir_exists", smoke_build.is_dir())
        .assertion("run_only_uv_node_passed", run_only.returncode == 0)
        .assertion("selected_node_was_uv", "  [1/1] user.seeded (uv)" in run_only_output)
        .assertion("did_not_continue_downstream", "  [2/" not in run_only_output)
        .assertion("run_only_rejects_rerun_false", rerun_false.returncode != 0)
        .assertion("run_only_rerun_false_explained", "rerun=false" in rerun_false_output)
        .log(summarize("baseline_smoke", baseline))
        .log(summarize("run_only_user_seeded", run_only))
        .log(summarize("run_only_rerun_false", rerun_false))
        .publish("smokeRunId", run_id)
    )
    if smoke_build.is_dir():
        result.artifact("project-smoke-report", str(smoke_build / "report.md"))
    return result


if __name__ == "__main__":
    main()

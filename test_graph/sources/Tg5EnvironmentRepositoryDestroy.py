# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
from __future__ import annotations

import os

from testgraphsdk import EnvironmentRepository, NodeResult, NodeSpec, SideEffect, node


REPOSITORY = EnvironmentRepository.of(
    "build/tg5-environment-repository-source",
    "templates/local-preview",
).with_output_keys("EnvironmentId", "KUBECONFIG", "KUBECONTEXT")

DEPLOY = "tg5.environment.repository.deploy"
DESTROY_KEYS = ("TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT", "TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT")
TRUTHY = {"1", "true", "yes", "y"}


def destroy_requested() -> bool:
    return any(os.environ.get(key, "").strip().lower() in TRUTHY for key in DESTROY_KEYS)


spec_builder = (
    NodeSpec("tg5.environment.repository.destroy")
    .kind("action")
    .depends_on(DEPLOY)
    .tags("tg5", "environment", "destroy")
    .output("destroyRequested")
)

if destroy_requested():
    spec_builder = spec_builder.side_effects(SideEffect.environment("destroy")).environment_repository(REPOSITORY)

SPEC = spec_builder


@node(SPEC)
def main(ctx):
    requested = destroy_requested()
    return (
        NodeResult.pass_(ctx.node_id)
        .assertion("destroy_guard_state_is_explicit", requested or all(not os.environ.get(key) for key in DESTROY_KEYS))
        .publish("destroyRequested", str(requested).lower())
    )


if __name__ == "__main__":
    main()

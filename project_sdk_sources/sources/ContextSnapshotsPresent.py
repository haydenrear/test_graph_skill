# /// script
# requires-python = ">=3.10"
# dependencies = ["testgraphsdk"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# ///
"""Assertion node: verifies per-node input context snapshots exist."""
from __future__ import annotations

import json

from testgraphsdk import NodeResult, NodeSpec, node


UPSTREAM_NODE_IDS = [
    "app.running",
    "user.seeded",
    "network.pingable",
    "login.smoke",
    "rerun.disabled.probe",
]

SPEC = (
    NodeSpec("context.snapshots.present")
    .kind("assertion")
    .depends_on("login.smoke", "rerun.disabled.probe")
    .tags("smoke", "resume")
    .timeout("30s")
    .output("snapshotCount", "integer")
)


def _snapshot_name(node_id: str) -> str:
    safe = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in node_id)
    return f"{safe}.input.json"


@node(SPEC)
def main(ctx):
    context_dir = ctx.report_dir / "context"
    node_ids = UPSTREAM_NODE_IDS + [ctx.node_id]
    result = NodeResult.pass_(ctx.node_id)

    for node_id in node_ids:
        path = context_dir / _snapshot_name(node_id)
        exists = path.is_file()
        result.assertion(f"{node_id}.input_context_snapshot_exists", exists)
        if exists:
            try:
                payload = json.loads(path.read_text())
            except Exception:
                payload = {}
            result.assertion(
                f"{node_id}.input_context_snapshot_has_items_array",
                isinstance(payload.get("items"), list),
            )

    self_snapshot = context_dir / _snapshot_name(ctx.node_id)
    if self_snapshot.is_file():
        payload = json.loads(self_snapshot.read_text())
        seen = {item.get("nodeId") for item in payload.get("items", [])}
        result.assertion(
            "self_snapshot_contains_upstream_context",
            set(UPSTREAM_NODE_IDS).issubset(seen),
        )

    for node_id in UPSTREAM_NODE_IDS:
        envelope = ctx.upstream(node_id) or {}
        rel = envelope.get("inputContextFile")
        result.assertion(f"{node_id}.envelope_has_input_context_file", bool(rel))
        if rel:
            result.assertion(
                f"{node_id}.envelope_input_context_file_exists",
                (ctx.report_dir / rel).is_file(),
            )

    return (
        result
        .metric("snapshotCount", len(node_ids))
        .artifact("input-context-dir", str(context_dir))
        .publish("snapshotCount", str(len(node_ids)))
    )


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Explicitly migrate legacy provider symlinks to portable managed bindings."""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

from _common import (
    PROVIDER_BINDINGS,
    ProviderBindingError,
    add_test_graph_root_arg,
    ensure_provider_binding_ignores,
    prepare_provider_bindings,
    target_project_root,
    write_provider_bindings_manifest,
)


def _git_output(root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        text=True,
        capture_output=True,
        check=False,
    )


def _untrack_generated_links(root: Path) -> list[str]:
    top_level = _git_output(root, "rev-parse", "--show-toplevel")
    if top_level.returncode != 0:
        return []
    git_root = Path(top_level.stdout.strip()).resolve()
    tracked: list[str] = []
    for name in sorted(PROVIDER_BINDINGS):
        relative = (root / name).absolute().relative_to(git_root).as_posix()
        probe = _git_output(git_root, "ls-files", "--error-unmatch", "--", relative)
        if probe.returncode == 0:
            tracked.append(relative)
    if tracked:
        removed = _git_output(git_root, "rm", "--cached", "--quiet", "--", *tracked)
        if removed.returncode != 0:
            raise ProviderBindingError(
                "cannot untrack generated provider links: "
                f"{removed.stderr.strip()}"
            )
    return tracked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_test_graph_root_arg(parser)
    parser.add_argument(
        "--workspace-provider",
        help=(
            "Optional provider root relative to test_graph/. It is tried before "
            "the installed/running skill, which remains the fallback."
        ),
    )
    args = parser.parse_args()
    root = target_project_root(args.test_graph_root)
    real_paths = [
        root / name
        for name in sorted(PROVIDER_BINDINGS)
        if (root / name).exists() and not (root / name).is_symlink()
    ]
    if real_paths:
        parser.error(
            "refusing to replace copied provider paths: "
            + ", ".join(str(path) for path in real_paths)
        )
    try:
        write_provider_bindings_manifest(
            root,
            workspace_provider=args.workspace_provider,
        )
        ensure_provider_binding_ignores(root)
        untracked = _untrack_generated_links(root)
        result = prepare_provider_bindings(root)
    except (OSError, ValueError, ProviderBindingError) as error:
        parser.error(str(error))
    assert result is not None
    print(f"migrated Test Graph provider bindings at {root}")
    if untracked:
        print("  staged generated-link removals: " + ", ".join(untracked))
    print("  review provider-bindings.json, .gitignore, and git status; then commit them")
    return 0


if __name__ == "__main__":
    sys.exit(main())

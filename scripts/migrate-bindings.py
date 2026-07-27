#!/usr/bin/env python3
"""Explicitly migrate legacy provider symlinks to portable managed bindings.

Migration is all-or-nothing. Everything that can be decided without writing
is decided first - the provider is selected and the manifest document is
validated before the first byte is written - and the writes that remain are
undone as a group if any of them fails. A migration that does not finish
leaves the project exactly as it was found, still on the legacy path, because
a half-migrated project is worse than an unmigrated one: the wrappers see a
manifest, stop falling back to the legacy symlinks, and hard-exit.

For the same reason an explicitly passed ``--workspace-provider`` must resolve
here and now. The manifest this writes is committed and read by every consumer
of the repository, so a provider path that resolves nowhere has to be refused
at authoring time even though falling back to the installed skill is the right
behaviour at run time. See :func:`_require_workspace_provider`.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

from _common import (
    PROVIDER_BINDING_IGNORE_BEGIN,
    PROVIDER_BINDINGS,
    PROVIDER_BINDINGS_MANIFEST,
    ProviderBindingError,
    add_test_graph_root_arg,
    capture_managed_bindings,
    ensure_provider_binding_ignores,
    missing_provider_bindings,
    prepare_provider_bindings,
    provider_bindings_document,
    provider_root_is_complete,
    rollback_managed_bindings,
    select_provider_root,
    target_project_root,
    validate_provider_bindings_document,
    write_provider_bindings_manifest,
)


def _git_output(root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        text=True,
        capture_output=True,
        check=False,
    )


def _generated_link_index(root: Path) -> tuple[Path | None, list[str], list[str]]:
    """Read-only survey: (git root, tracked binding paths, their index entries).

    The entries are ``git ls-files -s`` lines, which is exactly the format
    ``git update-index --index-info`` consumes, so :func:`_restore_index`
    can put back the mode and blob the index held rather than approximating
    them from HEAD.
    """

    top_level = _git_output(root, "rev-parse", "--show-toplevel")
    if top_level.returncode != 0:
        return None, [], []
    git_root = Path(top_level.stdout.strip()).resolve()
    # Both sides resolved: git reports a physical path, and a caller-supplied
    # root may still be the /var -> /private/var kind of symlink.
    resolved_root = root.resolve()
    tracked: list[str] = []
    for name in sorted(PROVIDER_BINDINGS):
        relative = (resolved_root / name).relative_to(git_root).as_posix()
        probe = _git_output(git_root, "ls-files", "--error-unmatch", "--", relative)
        if probe.returncode == 0:
            tracked.append(relative)
    if not tracked:
        return git_root, [], []
    listed = _git_output(git_root, "ls-files", "-s", "--", *tracked)
    if listed.returncode != 0:
        raise ProviderBindingError(
            "cannot read index entries for generated provider links: "
            f"{listed.stderr.strip()}"
        )
    return git_root, tracked, listed.stdout.splitlines()


def _require_workspace_provider(root: Path, workspace_provider: str) -> None:
    """An explicitly passed --workspace-provider is an assertion, not a hint.

    Authoring time and run time are deliberately asymmetric here, and the two
    must not be "fixed" to match:

    * At run time - prepare-bindings.py and the wrappers - falling through a
      missing workspace provider to the installed skill is correct. The
      manifest is already committed and shared; the workspace copy can be
      legitimately absent on this machine or this CI runner, and the build
      still has to run.
    * At authoring time - here - the same fall-through is a silent defect. The
      unresolvable path is written into the manifest as the *first* candidate
      and committed, the command exits 0, and from then on every consumer of
      that repository skips the dead candidate and materializes an absolute
      skill-root link: precisely the absolute-path-in-the-tree failure managed
      bindings exist to remove. One mistyped path in a fan-out loop reproduces
      it across every repository at once, with a green exit each time.

    So a provider the caller named explicitly must resolve now. Omitting the
    flag is unchanged: the skill-root candidate stands alone and is legitimate.
    """

    resolved = (root / workspace_provider).resolve()
    if provider_root_is_complete(resolved):
        return
    raise ProviderBindingError(
        f"--workspace-provider {workspace_provider} does not carry a Test Graph "
        f"provider (resolved to {resolved}; missing "
        + ", ".join(missing_provider_bindings(resolved))
        + "). Refusing to fall back to the installed skill: that would commit "
        "this path as the manifest's first candidate and leave every consumer "
        "materializing an absolute link. Fix the path, or omit "
        "--workspace-provider to bind to the installed skill deliberately."
    )


def _untrack_generated_links(git_root: Path, tracked: list[str]) -> None:
    removed = _git_output(git_root, "rm", "--cached", "--quiet", "--", *tracked)
    if removed.returncode != 0:
        raise ProviderBindingError(
            "cannot untrack generated provider links: " f"{removed.stderr.strip()}"
        )


def _restore_index(git_root: Path, entries: list[str]) -> None:
    """Put the captured index entries back after a failed migration."""

    if not entries:
        return
    restored = subprocess.run(
        ["git", "-C", str(git_root), "update-index", "--index-info"],
        input="\n".join(entries) + "\n",
        text=True,
        capture_output=True,
        check=False,
    )
    if restored.returncode != 0:
        print(
            "warning: could not restore the Git index after a failed migration: "
            f"{restored.stderr.strip()}\n"
            "  restore it with:  git -C "
            f"{git_root} checkout HEAD -- " + " ".join(sorted(PROVIDER_BINDINGS)),
            file=sys.stderr,
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_test_graph_root_arg(parser)
    parser.add_argument(
        "--workspace-provider",
        help=(
            "Optional provider root relative to test_graph/, recorded as the "
            "manifest's first candidate. If given it must resolve now: "
            "migration refuses rather than quietly binding to the installed "
            "skill instead. Omit it to bind to the installed skill on purpose. "
            "At run time the installed skill remains the fallback."
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

    # Decide everything that can be decided without writing. A migration that
    # cannot complete must not start: refusing here costs the user nothing,
    # whereas a manifest left behind by a failed one takes the wrappers off
    # the working legacy path.
    try:
        document = provider_bindings_document(
            workspace_provider=args.workspace_provider,
        )
        validate_provider_bindings_document(document)
        if args.workspace_provider is not None:
            _require_workspace_provider(root, args.workspace_provider)
        select_provider_root(root, document)
        git_root, tracked, index_entries = _generated_link_index(root)
    except (OSError, ValueError, ProviderBindingError) as error:
        parser.error(str(error))

    # The writes that remain can still fail on the filesystem or in Git, so
    # they are applied as one group and undone as one group.
    before = capture_managed_bindings(root)
    untracked = False
    try:
        write_provider_bindings_manifest(
            root,
            workspace_provider=args.workspace_provider,
        )
        ensure_provider_binding_ignores(root)
        if tracked:
            assert git_root is not None
            _untrack_generated_links(git_root, tracked)
            untracked = True
        result = prepare_provider_bindings(root)
    except (OSError, ValueError, ProviderBindingError) as error:
        # The original error explains why the migration failed and is the more
        # useful of the two, so a rollback that fails is reported alongside it,
        # never instead of it.
        try:
            rollback_managed_bindings(root, before)
            # Only when the index really changed: `git rm --cached` writes the
            # index once, under lock, so a failed one left nothing to put back.
            if untracked:
                assert git_root is not None
                _restore_index(git_root, index_entries)
        except OSError as rollback_error:
            parser.error(
                f"{error}\n"
                f"  the rollback then failed too: {rollback_error}\n"
                f"  {root} is partially migrated: delete "
                f"{PROVIDER_BINDINGS_MANIFEST}, drop the "
                f"{PROVIDER_BINDING_IGNORE_BEGIN} block from .gitignore, and "
                "restore the generated links with `git checkout HEAD --`"
            )
        parser.error(f"{error}\n  migration rolled back; the project is unchanged")
    assert result is not None
    print(f"migrated Test Graph provider bindings at {root}")
    if tracked:
        print("  staged generated-link removals: " + ", ".join(tracked))
    print("  review provider-bindings.json, .gitignore, and git status; then commit them")
    return 0


if __name__ == "__main__":
    sys.exit(main())

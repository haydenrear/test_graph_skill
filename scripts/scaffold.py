#!/usr/bin/env python3
"""Scaffold a test_graph project into <repo-root>/test_graph/.

Copies the contents of ``project_sdk_sources/`` (SDKs, Gradle plugin,
build files, example node scripts, Gradle wrapper) into a new
``test_graph/`` subdirectory under the given repo root. The target
``test_graph/`` must not already exist or must be empty.

The ``sdk/``, ``build-logic/``, and ``standard-nodes/`` subtrees are created
as **symlinks** rather than copies, so upstream upgrades land in every consumer
scaffold without rsync. That's why the rest of the scaffold (sources/,
build.gradle.kts, gradle wrapper, examples) stays as a copy: those are
user-edited.

Those symlinks are always **relative**, and always point at a copy of
``project_sdk_sources/`` that lives inside the consuming tree - either the
project's own ``.skill-manager`` home or a skill checkout vendored in the repo.
They are never absolute and never leave the tree, because they get committed:
an absolute link baked in at scaffold time only resolves on the machine that
ran the scaffolder, and no environment override (``SKILL_MANAGER_HOME``,
``-Duser.home``) can redirect a path that is already stamped into a Git blob.

Usage:
    scaffold.py <repo-root>
    scaffold.py <repo-root> --copy-sdk    # snapshot copies instead of symlinks

Example:
    scaffold.py ~/projects/myapp
        creates ~/projects/myapp/test_graph/ populated with the scaffold;
        sdk/, build-logic/, and standard-nodes/ become relative symlinks into
        ~/projects/myapp/.skill-manager/skills/test-graph/project_sdk_sources/.
"""
from __future__ import annotations

import argparse
import os
import shutil
import stat
import sys
from pathlib import Path

from _common import project_sdk_sources


# Subtrees scaffolded as symlinks rather than copies. Updating the skill
# propagates instantly to every consumer scaffold - no manual rsync, no
# drift between project copies. The flip side: remove the skill from the
# project's home and every consumer symlink dangles, so re-run
# `skill-manager sync` rather than deleting the vendored skill.
SYMLINK_TARGETS = {"sdk", "build-logic", "standard-nodes"}

# Directory name of a skill-manager home. Each checkout owns one; see the
# git-integration-repo skill's references/skill-homes.md.
HOME_DIRNAME = ".skill-manager"

# Where a skill-manager home vendors this skill's scaffold sources.
VENDORED_SDK_SOURCES = Path("skills") / "test-graph" / "project_sdk_sources"

PROVIDER_VALIDATION_BEGIN = "    // TEST-GRAPH-PROVIDER-VALIDATION-BEGIN\n"
PROVIDER_VALIDATION_END = "    // TEST-GRAPH-PROVIDER-VALIDATION-END\n"


def _ignore(dirname: str, names: list[str]) -> list[str]:
    # Skip build outputs, caches, and VCS artifacts if the source has them.
    skip = set()
    for name in names:
        if name in {".gradle", "build", "out", ".idea", "__pycache__", "node_modules"}:
            skip.add(name)
        elif name.endswith(".egg-info"):
            skip.add(name)
    return list(skip)


def nearest_enclosing_home(start: Path) -> Path | None:
    """Nearest ``.skill-manager`` home at or above ``start``.

    Walked, never templated. A consumer can sit at any depth below the
    home that owns it: ``meta-orchestrator/constituents/stream-lite`` is
    two integration levels down, so a hardcoded ``../`` count cannot
    express the distance. Walking up and then taking ``os.path.relpath``
    from the link's own directory is the only formulation that is right
    at every depth.

    The returned path is *not* resolved. If ``.skill-manager`` is itself
    a symlink we still want to route through the project-local name, so
    the committed link stays inside the project tree.
    """
    current = start
    while True:
        candidate = current / HOME_DIRNAME
        if candidate.is_dir():
            return candidate
        if current.parent == current:
            return None
        current = current.parent


def symlink_source_root(repo_root: Path, src: Path) -> Path:
    """Directory the scaffold's symlinks must point into.

    Two ways a consuming tree can own a copy of ``project_sdk_sources/``:

    1. The skill checkout is already inside ``repo_root`` (this skill's
       own nested ``test_graph/``, or a repo that vendors the checkout).
       Point straight at it.
    2. A ``.skill-manager`` home encloses ``repo_root``. Point at the
       copy that home vendors.

    What is deliberately ignored: ``src`` when it sits *outside* the
    tree. That is where the bug came from - ``project_sdk_sources()``
    resolves against whatever ``SKILL_MANAGER_HOME`` pointed at when the
    scaffolder ran, in practice the operator's global home, and that
    absolute path then got committed. Where the scaffolder happens to be
    installed is not a property of the project being scaffolded.
    """
    if src.is_relative_to(repo_root):
        return src

    home = nearest_enclosing_home(repo_root)
    if home is None:
        # Chosen fallback: refuse. The alternative - keep writing the
        # absolute path and print a warning - is not a fallback, it is
        # the defect with a log line attached. The link is committed, so
        # a warning is seen once by the person who already has a working
        # machine and never by the people the broken link reaches (CI,
        # every other developer). Nothing downstream can repair it:
        # neither SKILL_MANAGER_HOME nor -Duser.home can redirect a path
        # frozen into a Git blob, which is why test isolation was
        # structurally unachievable and Gradle wrote build state into the
        # operator's real home on every graph run. Failing here costs one
        # command; succeeding wrongly costs a repo-wide symlink repair.
        sys.exit(
            f"error: no {HOME_DIRNAME}/ home encloses {repo_root}, and the "
            f"test-graph skill is not vendored inside it.\n"
            f"  sdk/, build-logic/, and standard-nodes/ are committed symlinks. "
            f"They must resolve inside the project tree or the checkout only "
            f"builds on this machine.\n"
            f"  Fix by either:\n"
            f"    1. creating the project's own home first, then re-running:\n"
            f"         SKILL_MANAGER_HOME={repo_root / HOME_DIRNAME} \\\n"
            f"           skill-manager install github:haydenrear/test_graph_skill\n"
            f"    2. re-running with --copy-sdk for a self-contained snapshot "
            f"(no symlinks, no upstream propagation)."
        )

    vendored = home / VENDORED_SDK_SOURCES
    if not vendored.is_dir():
        # Emitted anyway: the shape is already correct for this project,
        # and `skill-manager sync` against the project home populates it.
        # Refusing here would make scaffolding order-dependent for no
        # correctness gain - unlike the no-home case, nothing absolute is
        # being written.
        print(
            f"warning: {vendored} does not exist yet - the scaffold's symlinks "
            f"will dangle until the test-graph skill is installed into "
            f"{home}.\n"
            f"  SKILL_MANAGER_HOME={home} skill-manager sync",
            file=sys.stderr,
        )
    return vendored


def _remove_provider_validation_sections(build_file: Path) -> None:
    """Keep provider acceptance graphs out of newly scaffolded consumers."""
    content = build_file.read_text(encoding="utf-8")
    begin_count = content.count(PROVIDER_VALIDATION_BEGIN)
    end_count = content.count(PROVIDER_VALIDATION_END)
    if begin_count != end_count:
        raise RuntimeError(
            f"unbalanced provider-validation markers in {build_file}: "
            f"{begin_count} begin, {end_count} end"
        )
    while PROVIDER_VALIDATION_BEGIN in content:
        begin = content.index(PROVIDER_VALIDATION_BEGIN)
        end = content.index(PROVIDER_VALIDATION_END, begin) + len(PROVIDER_VALIDATION_END)
        content = content[:begin] + content[end:]
    build_file.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "repo_root",
        help="your project's repo root - scaffold goes into <repo_root>/test_graph/",
    )
    parser.add_argument(
        "--copy-sdk",
        action="store_true",
        help="Snapshot-copy sdk/, build-logic/, and standard-nodes/ instead of symlinking. "
             "Use when the consumer needs to be self-contained "
             "(detached environments, archives, Windows without "
             "developer-mode symlink permission).",
    )
    args = parser.parse_args()

    repo_root = Path(args.repo_root).expanduser().resolve()
    if not repo_root.exists():
        repo_root.mkdir(parents=True)
    if not repo_root.is_dir():
        sys.exit(f"error: {repo_root} exists and is not a directory")

    target = repo_root / "test_graph"
    if target.exists() and any(target.iterdir()):
        sys.exit(
            f"error: {target} already exists and is not empty.\n"
            "  remove or empty it first, then re-run."
        )

    src = project_sdk_sources().resolve()
    if not src.is_dir():
        sys.exit(
            f"error: project_sdk_sources/ not found at {src} - this skill "
            "repo may be broken."
        )

    # Resolved before anything is written, so a refusal leaves no
    # half-scaffolded test_graph/ behind.
    link_root = src if args.copy_sdk else symlink_source_root(repo_root, src)

    target.mkdir(parents=True, exist_ok=True)

    symlinks_used: list[str] = []
    for child in src.iterdir():
        dest = target / child.name
        if child.name in SYMLINK_TARGETS and not args.copy_sdk:
            # Relative to the link's own directory, so the whole scaffold
            # can be moved, cloned, or checked out anywhere and the link
            # still lands on the copy this project owns.
            link_target = os.path.relpath(link_root / child.name, start=target)
            try:
                os.symlink(link_target, dest, target_is_directory=child.is_dir())
                symlinks_used.append(child.name)
                continue
            except OSError as e:
                # Falling back to a copy keeps us working on Windows
                # without developer-mode and on filesystems that reject
                # symlinks (some FUSE mounts). Note loudly.
                print(
                    f"warning: could not symlink {child.name} (falling back to copy): {e}",
                    file=sys.stderr,
                )
        if child.is_dir():
            shutil.copytree(child, dest, ignore=_ignore, dirs_exist_ok=False)
        else:
            shutil.copy2(child, dest)

    # Make gradlew executable (shutil.copy2 preserves perms on most FS, but not all).
    build_file = target / "build.gradle.kts"
    if build_file.is_file():
        _remove_provider_validation_sections(build_file)

    gw = target / "gradlew"
    if gw.exists():
        gw.chmod(gw.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    print(f"scaffolded test_graph project at {target}")
    if symlinks_used:
        print(f"  relative symlinks: {', '.join(sorted(symlinks_used))}")
        print(f"  (into {link_root}; changes there take effect immediately)")
    print()
    scripts = Path(__file__).resolve().parent
    print("next steps:")
    print(f"  cd {repo_root}")
    print(f"  {scripts / 'discover.py'}")
    print(f"  {scripts / 'discover.py'} smoke")
    print(f"  {scripts / 'run.py'} smoke")
    print(f"  {scripts / 'run.py'} --all")
    return 0


if __name__ == "__main__":
    sys.exit(main())

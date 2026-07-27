"""Scaffolded symlinks must stay relative and stay inside the project tree.

The defect these cover: the scaffolder used to stamp
``project_sdk_sources()`` - i.e. whatever ``SKILL_MANAGER_HOME`` pointed
at when it ran, in practice the operator's global home - into an
absolute symlink that then got committed. Thirteen such links were found
tracked across five repositories. Nothing downstream can repair one:
neither ``SKILL_MANAGER_HOME`` nor ``-Duser.home`` redirects a path
already frozen into a Git blob.
"""
from __future__ import annotations

import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import scaffold  # noqa: E402


LINK_NAMES = ("sdk", "build-logic", "standard-nodes")


def _make_sdk_sources(root: Path) -> Path:
    """A minimal stand-in for the skill's project_sdk_sources/."""
    src = root / "project_sdk_sources"
    for name in LINK_NAMES:
        (src / name).mkdir(parents=True)
        (src / name / "marker.txt").write_text(name, encoding="utf-8")
    (src / "sources").mkdir()
    (src / "build.gradle.kts").write_text("validationGraph {}\n", encoding="utf-8")
    (src / "settings.gradle.kts").write_text("rootProject.name = \"test_graph\"\n", encoding="utf-8")
    return src


def _make_home(home: Path) -> Path:
    """A skill-manager home with this skill vendored into it.

    Returns the vendored ``project_sdk_sources/`` the links should reach.
    """
    home.mkdir(parents=True, exist_ok=True)
    return _make_sdk_sources(home / "skills" / "test-graph")


def _scaffold(repo_root: Path, src: Path, *extra: str) -> str:
    """Run the scaffolder as the CLI does, returning captured stdout."""
    argv = ["scaffold.py", str(repo_root), *extra]
    out = io.StringIO()
    with patch.object(scaffold, "project_sdk_sources", return_value=src):
        with patch.object(sys, "argv", argv):
            with contextlib.redirect_stdout(out):
                scaffold.main()
    return out.getvalue()


class NearestEnclosingHomeTest(unittest.TestCase):
    def test_walks_up_an_arbitrary_number_of_levels(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            (root / "outer" / ".skill-manager").mkdir(parents=True)
            deep = root / "outer" / "constituents" / "stream-lite" / "nested"
            deep.mkdir(parents=True)

            self.assertEqual(
                root / "outer" / ".skill-manager",
                scaffold.nearest_enclosing_home(deep),
            )

    def test_prefers_the_nearest_home(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            (root / "outer" / ".skill-manager").mkdir(parents=True)
            inner = root / "outer" / "inner"
            (inner / ".skill-manager").mkdir(parents=True)

            self.assertEqual(
                inner / ".skill-manager",
                scaffold.nearest_enclosing_home(inner),
            )

    def test_returns_none_when_no_home_encloses_the_path(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            orphan = Path(tmp).resolve() / "orphan"
            orphan.mkdir()
            # A real filesystem walk can reach $HOME or /, so only assert
            # None when nothing above the temp dir happens to own a home.
            if scaffold.nearest_enclosing_home(Path(tmp).resolve().parent) is None:
                self.assertIsNone(scaffold.nearest_enclosing_home(orphan))


class ScaffoldSymlinkShapeTest(unittest.TestCase):
    def assert_links_are_relative(self, test_graph: Path) -> None:
        for name in LINK_NAMES:
            link = test_graph / name
            self.assertTrue(link.is_symlink(), f"{name} should be a symlink")
            target = os.readlink(link)
            self.assertFalse(
                os.path.isabs(target),
                f"{name} -> {target} is absolute; it would be committed and "
                "resolve only on the machine that scaffolded it",
            )

    def assert_links_resolve_into(self, test_graph: Path, sources: Path) -> None:
        for name in LINK_NAMES:
            link = test_graph / name
            self.assertTrue(link.is_dir(), f"{name} does not resolve")
            self.assertEqual((sources / name).resolve(), link.resolve())
            self.assertEqual(name, (link / "marker.txt").read_text(encoding="utf-8"))

    def test_home_at_project_root_yields_a_single_level_relative_link(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp).resolve() / "myapp"
            repo.mkdir()
            vendored = _make_home(repo / ".skill-manager")
            # Scaffolder invoked from some *other* checkout of the skill,
            # exactly as the operator's global home was.
            elsewhere = _make_sdk_sources(Path(tmp).resolve() / "global-home")

            _scaffold(repo, elsewhere)

            test_graph = repo / "test_graph"
            self.assert_links_are_relative(test_graph)
            self.assertEqual(
                "../.skill-manager/skills/test-graph/project_sdk_sources/sdk",
                os.readlink(test_graph / "sdk"),
            )
            self.assert_links_resolve_into(test_graph, vendored)

    def test_two_levels_deep_computes_the_path_it_cannot_template(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            outer = Path(tmp).resolve() / "meta-orchestrator"
            vendored = _make_home(outer / ".skill-manager")
            repo = outer / "constituents" / "stream-lite"
            repo.mkdir(parents=True)
            elsewhere = _make_sdk_sources(Path(tmp).resolve() / "global-home")

            _scaffold(repo, elsewhere)

            test_graph = repo / "test_graph"
            self.assert_links_are_relative(test_graph)
            self.assertEqual(
                "../../../.skill-manager/skills/test-graph/project_sdk_sources/sdk",
                os.readlink(test_graph / "sdk"),
            )
            self.assert_links_resolve_into(test_graph, vendored)

    def test_links_survive_relocating_the_whole_tree(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            repo = root / "myapp"
            repo.mkdir()
            _make_home(repo / ".skill-manager")
            _scaffold(repo, _make_sdk_sources(root / "global-home"))

            moved = root / "cloned-somewhere-else"
            repo.rename(moved)

            self.assert_links_resolve_into(
                moved / "test_graph",
                moved / ".skill-manager" / "skills" / "test-graph" / "project_sdk_sources",
            )

    def test_skill_vendored_inside_the_repo_is_linked_directly(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp).resolve() / "repo"
            repo.mkdir()
            vendored = _make_sdk_sources(repo / "vendor" / "test-graph")

            _scaffold(repo, vendored)

            test_graph = repo / "test_graph"
            self.assert_links_are_relative(test_graph)
            self.assertEqual(
                "../vendor/test-graph/project_sdk_sources/sdk",
                os.readlink(test_graph / "sdk"),
            )
            self.assert_links_resolve_into(test_graph, vendored)

    def test_non_symlinked_entries_are_still_copied(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp).resolve() / "myapp"
            repo.mkdir()
            _make_home(repo / ".skill-manager")
            _scaffold(repo, _make_sdk_sources(Path(tmp).resolve() / "global-home"))

            test_graph = repo / "test_graph"
            for name in ("sources", "build.gradle.kts", "settings.gradle.kts"):
                entry = test_graph / name
                self.assertTrue(entry.exists(), f"{name} missing")
                self.assertFalse(entry.is_symlink(), f"{name} should be a copy")


class NoEnclosingHomeTest(unittest.TestCase):
    def test_refuses_rather_than_writing_an_absolute_link(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            repo = root / "orphan"
            repo.mkdir()
            elsewhere = _make_sdk_sources(root / "global-home")

            with patch.object(scaffold, "nearest_enclosing_home", return_value=None):
                with self.assertRaises(SystemExit) as raised:
                    _scaffold(repo, elsewhere)

            message = str(raised.exception)
            self.assertIn("--copy-sdk", message)
            self.assertIn(".skill-manager", message)
            self.assertFalse(
                (repo / "test_graph").exists(),
                "a refused scaffold must not leave a half-written test_graph/",
            )

    def test_copy_sdk_still_works_without_any_home(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve()
            repo = root / "orphan"
            repo.mkdir()
            elsewhere = _make_sdk_sources(root / "global-home")

            with patch.object(scaffold, "nearest_enclosing_home", return_value=None):
                _scaffold(repo, elsewhere, "--copy-sdk")

            test_graph = repo / "test_graph"
            for name in LINK_NAMES:
                entry = test_graph / name
                self.assertTrue(entry.is_dir())
                self.assertFalse(entry.is_symlink(), f"{name} should be a snapshot copy")
                self.assertEqual(name, (entry / "marker.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()

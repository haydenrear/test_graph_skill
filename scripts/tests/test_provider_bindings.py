from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
SKILL_ROOT = SCRIPTS_DIR.parent
sys.path.insert(0, str(SCRIPTS_DIR))

import _common  # noqa: E402


def _scaffold_root(parent: Path) -> Path:
    root = parent / "consumer" / "test_graph"
    root.mkdir(parents=True)
    (root / "settings.gradle.kts").write_text(
        'rootProject.name = "fixture"\n', encoding="utf-8"
    )
    return root


def _provider_root(parent: Path) -> Path:
    root = parent / "provider"
    for relative in _common.PROVIDER_BINDINGS.values():
        (root / relative).mkdir(parents=True)
    return root


def _load_migrate_module():
    """Import migrate-bindings.py, whose hyphenated name blocks `import`."""
    spec = importlib.util.spec_from_file_location(
        "migrate_bindings", SCRIPTS_DIR / "migrate-bindings.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _git(repo: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *arguments],
        text=True,
        capture_output=True,
        check=True,
    ).stdout


def _legacy_git_project(parent: Path) -> tuple[Path, Path, Path]:
    """A committed pre-managed scaffold: tracked links, no manifest.

    Returns ``(repo, test_graph_root, provider_root)``.
    """
    repo = parent / "repo"
    root = _scaffold_root(repo)
    (root / "build.gradle.kts").write_text("validationGraph { }\n", encoding="utf-8")
    (root / ".gitignore").write_text(
        "# project rules that predate managed bindings\n/docs\n/reports\n",
        encoding="utf-8",
    )
    provider = _provider_root(repo)
    for name, relative in _common.PROVIDER_BINDINGS.items():
        os.symlink(provider / relative, root / name, target_is_directory=True)
    (repo / "keep.txt").write_text("keep\n", encoding="utf-8")
    for command in (
        ["init", "-q"],
        ["config", "user.name", "Test Graph"],
        ["config", "user.email", "test-graph@example.invalid"],
        ["add", "."],
        ["commit", "-q", "-m", "legacy scaffold"],
    ):
        _git(repo, *command)
    return repo, root, provider


def _skill_install_without_provider(parent: Path) -> Path:
    """A test-graph install whose project_sdk_sources/ is not there.

    This is the shape that makes provider selection fail for real: the
    scripts are present and runnable, so ``skill_root()`` resolves, but the
    skill-root candidate carries none of the three bindings.
    """
    skill = parent / "skill-without-provider"
    (skill / "scripts").mkdir(parents=True)
    for script in SCRIPTS_DIR.glob("*.py"):
        shutil.copy2(script, skill / "scripts" / script.name)
    return skill


def _fake_gradlew(root: Path, output: str) -> None:
    gradlew = root / "gradlew"
    gradlew.write_text(f"#!/bin/sh\necho '{output}'\n", encoding="utf-8")
    gradlew.chmod(gradlew.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


class ProviderBindingsTest(unittest.TestCase):
    def test_repo_root_gradle_build_does_not_shadow_child_test_graph(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "consumer"
            repo.mkdir()
            (repo / "settings.gradle.kts").write_text(
                'rootProject.name = "consumer"\n', encoding="utf-8"
            )
            (repo / "build.gradle.kts").write_text(
                'plugins { java }\n', encoding="utf-8"
            )
            test_graph = repo / "test_graph"
            test_graph.mkdir()
            (test_graph / "settings.gradle.kts").write_text(
                'rootProject.name = "validation"\n', encoding="utf-8"
            )
            (test_graph / "build.gradle.kts").write_text(
                'validationGraph { }\n', encoding="utf-8"
            )

            with patch.object(_common.Path, "cwd", return_value=repo):
                self.assertEqual(test_graph.resolve(), _common.target_project_root())

    def test_new_scaffold_uses_ignored_managed_bindings_and_ci_preparation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"
            scaffolded = subprocess.run(
                [sys.executable, str(SCRIPTS_DIR / "scaffold.py"), str(repo)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, scaffolded.returncode, scaffolded.stderr)
            root = repo / "test_graph"
            self.assertTrue((root / _common.PROVIDER_BINDINGS_MANIFEST).is_file())
            for name in _common.PROVIDER_BINDINGS:
                self.assertTrue((root / name).is_symlink())
            ignored = (root / ".gitignore").read_text(encoding="utf-8")
            self.assertIn(_common.PROVIDER_BINDING_IGNORE_BEGIN, ignored)

            workflow = subprocess.run(
                [sys.executable, str(SCRIPTS_DIR / "github-action.py"), str(repo)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, workflow.returncode, workflow.stderr)
            workflow_text = (
                repo / ".github/workflows/test-graph.yml"
            ).read_text(encoding="utf-8")
            self.assertIn("scripts/prepare-bindings.py", workflow_text)
            self.assertIn("Legacy compatibility", workflow_text)

    def test_legacy_links_warn_without_mutation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = _scaffold_root(parent)
            provider = _provider_root(parent)
            for name, relative in _common.PROVIDER_BINDINGS.items():
                os.symlink(provider / relative, root / name, target_is_directory=True)
            before = {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS}

            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                _common.prepare_provider_bindings_or_warn(root)

            self.assertIn("legacy Test Graph provider symlinks remain supported", stderr.getvalue())
            self.assertEqual(
                before,
                {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS},
            )
            self.assertFalse(_common.provider_bindings_manifest(root).exists())

    def test_workspace_provider_is_preferred_and_links_are_relative(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            root = _scaffold_root(parent)
            provider = _provider_root(parent)
            workspace_relative = os.path.relpath(provider, root)
            _common.write_provider_bindings_manifest(
                root, workspace_provider=workspace_relative
            )

            result = _common.prepare_provider_bindings(root)

            self.assertIsNotNone(result)
            assert result is not None
            self.assertEqual("workspace-relative", result["provider"]["kind"])
            for name, relative in _common.PROVIDER_BINDINGS.items():
                link = root / name
                self.assertTrue(link.is_symlink())
                self.assertFalse(Path(os.readlink(link)).is_absolute())
                self.assertEqual(
                    (provider / relative).resolve(),
                    link.resolve(strict=True),
                )

    def test_missing_workspace_provider_falls_back_to_running_skill(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _scaffold_root(Path(temporary))
            _common.write_provider_bindings_manifest(
                root, workspace_provider="../missing-provider"
            )

            result = _common.prepare_provider_bindings(root)

            self.assertIsNotNone(result)
            assert result is not None
            self.assertEqual("skill-root", result["provider"]["kind"])
            for name in _common.PROVIDER_BINDINGS:
                self.assertTrue(Path(os.readlink(root / name)).is_absolute())

    def test_real_copy_mode_path_is_never_replaced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _scaffold_root(Path(temporary))
            _common.write_provider_bindings_manifest(root)
            (root / "sdk").mkdir()

            with self.assertRaisesRegex(
                _common.ProviderBindingError, "refuses to replace real paths"
            ):
                _common.prepare_provider_bindings(root)

    def test_migration_untracks_only_generated_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary) / "repo"
            root = _scaffold_root(repo)
            provider = _provider_root(repo)
            for name, relative in _common.PROVIDER_BINDINGS.items():
                os.symlink(provider / relative, root / name, target_is_directory=True)
            (repo / "keep.txt").write_text("keep\n", encoding="utf-8")
            for command in (
                ["git", "init", "-q"],
                ["git", "config", "user.name", "Test Graph"],
                ["git", "config", "user.email", "test-graph@example.invalid"],
                ["git", "add", "."],
                ["git", "commit", "-q", "-m", "legacy scaffold"],
            ):
                subprocess.run(command, cwd=repo, check=True)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "migrate-bindings.py"),
                    "--test-graph-root",
                    str(root),
                    "--workspace-provider",
                    os.path.relpath(provider, root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            tracked = subprocess.run(
                ["git", "ls-files"], cwd=repo, text=True, capture_output=True, check=True
            ).stdout.splitlines()
            self.assertIn("keep.txt", tracked)
            for name in _common.PROVIDER_BINDINGS:
                self.assertNotIn(f"consumer/test_graph/{name}", tracked)
                self.assertTrue((root / name).is_symlink())
            manifest = json.loads(
                (root / _common.PROVIDER_BINDINGS_MANIFEST).read_text(encoding="utf-8")
            )
            self.assertEqual(_common.PROVIDER_BINDINGS_SCHEMA, manifest["schema_version"])
            self.assertIn("/sdk", (root / ".gitignore").read_text(encoding="utf-8"))

    def test_run_gradle_prepares_managed_bindings_before_launch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = _scaffold_root(Path(temporary))
            (root / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
            _common.write_provider_bindings_manifest(root)

            with patch.object(
                _common.subprocess,
                "run",
                return_value=type("Result", (), {"returncode": 0})(),
            ) as run:
                self.assertEqual(0, _common.run_gradle(["smoke"], root))

            for name in _common.PROVIDER_BINDINGS:
                self.assertTrue((root / name).is_symlink())
            run.assert_called_once()


class MigrationAtomicityTest(unittest.TestCase):
    """A migration that cannot finish must not start, and must leave no trace.

    The failure being pinned: the manifest, the ignore block and the staged
    link removals used to land before the provider was ever resolved. A
    project left in that state is worse than an unmigrated one -
    ``prepare_provider_bindings_or_warn`` sees a manifest, stops falling back
    to the still-working legacy symlinks, and hard-exits every wrapper.
    """

    def _failing_migration(
        self, root: Path, parent: Path
    ) -> subprocess.CompletedProcess[str]:
        skill = _skill_install_without_provider(parent)
        return subprocess.run(
            [
                sys.executable,
                str(skill / "scripts" / "migrate-bindings.py"),
                "--test-graph-root",
                str(root),
                "--workspace-provider",
                "../mistyped-provider",
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_failed_migration_leaves_no_trace(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, _provider = _legacy_git_project(parent)
            # Unrelated staged work: a rollback must not reach past its own
            # three paths (which `git reset` or `git checkout -- .` would).
            (repo / "keep.txt").write_text("keep editing\n", encoding="utf-8")
            _git(repo, "add", "keep.txt")

            ignore_before = (root / ".gitignore").read_bytes()
            status_before = _git(repo, "status", "--porcelain")
            links_before = {
                name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS
            }

            completed = self._failing_migration(root, parent)

            self.assertNotEqual(0, completed.returncode, completed.stdout)
            self.assertIn("no complete Test Graph provider", completed.stderr)
            self.assertFalse(
                _common.provider_bindings_manifest(root).exists(),
                "a manifest from a failed migration takes the wrappers off the "
                "legacy path with nothing to replace it",
            )
            self.assertEqual(
                ignore_before,
                (root / ".gitignore").read_bytes(),
                "the managed-bindings ignore block outlived the failed migration",
            )
            self.assertEqual(
                status_before,
                _git(repo, "status", "--porcelain"),
                "the failed migration left staged index changes behind",
            )
            self.assertEqual(
                links_before,
                {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS},
            )

    def test_a_locked_index_rolls_the_migration_back(self) -> None:
        """The failure that lands *after* the provider resolves.

        Resolving up front cannot help here - a concurrent Git process takes
        ``index.lock`` while the manifest and the ignore block are already
        written - so only the rollback keeps the invariant.
        """
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(parent)
            ignore_before = (root / ".gitignore").read_bytes()
            status_before = _git(repo, "status", "--porcelain")
            links_before = {
                name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS
            }
            lock = repo / ".git" / "index.lock"
            lock.write_text("", encoding="utf-8")

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "migrate-bindings.py"),
                    "--test-graph-root",
                    str(root),
                    "--workspace-provider",
                    os.path.relpath(provider, root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )
            lock.unlink()

            self.assertNotEqual(0, completed.returncode, completed.stdout)
            self.assertIn("cannot untrack generated provider links", completed.stderr)
            self.assertFalse(_common.provider_bindings_manifest(root).exists())
            self.assertEqual(ignore_before, (root / ".gitignore").read_bytes())
            self.assertEqual(status_before, _git(repo, "status", "--porcelain"))
            self.assertEqual(
                links_before,
                {name: os.readlink(root / name) for name in _common.PROVIDER_BINDINGS},
            )

    def test_discover_still_runs_on_the_legacy_path_after_a_failed_migration(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, _provider = _legacy_git_project(parent)
            _fake_gradlew(root, "graphs: smoke")

            self.assertNotEqual(0, self._failing_migration(root, parent).returncode)

            discovered = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "discover.py"),
                    "--test-graph-root",
                    str(root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, discovered.returncode, discovered.stderr)
            self.assertIn("graphs: smoke", discovered.stdout)
            self.assertIn(
                "legacy Test Graph provider symlinks remain supported",
                discovered.stderr,
            )

    def test_successful_migration_is_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, provider = _legacy_git_project(parent)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "migrate-bindings.py"),
                    "--test-graph-root",
                    str(root),
                    "--workspace-provider",
                    os.path.relpath(provider, root),
                ],
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn("migrated Test Graph provider bindings", completed.stdout)
            self.assertIn("staged generated-link removals", completed.stdout)
            document = json.loads(
                _common.provider_bindings_manifest(root).read_text(encoding="utf-8")
            )
            self.assertEqual(
                "workspace-relative",
                document["provider_candidates"][0]["kind"],
            )
            ignored = (root / ".gitignore").read_text(encoding="utf-8")
            self.assertIn("# project rules that predate managed bindings", ignored)
            self.assertIn(_common.PROVIDER_BINDING_IGNORE_BEGIN, ignored)
            staged = _git(repo, "status", "--porcelain").splitlines()
            for name in _common.PROVIDER_BINDINGS:
                self.assertIn(f"D  consumer/test_graph/{name}", staged)
                link = root / name
                self.assertTrue(link.is_symlink())
                self.assertFalse(Path(os.readlink(link)).is_absolute())

    def test_rollback_restores_captured_bindings_byte_for_byte(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            _repo, root, provider = _legacy_git_project(parent)
            before = _common.capture_managed_bindings(root)

            _common.write_provider_bindings_manifest(
                root, workspace_provider=os.path.relpath(provider, root)
            )
            _common.ensure_provider_binding_ignores(root)
            _common.prepare_provider_bindings(root)
            _common.rollback_managed_bindings(root, before)

            self.assertFalse(_common.provider_bindings_manifest(root).exists())
            self.assertEqual(before.gitignore, (root / ".gitignore").read_bytes())
            self.assertEqual(
                before.links,
                {
                    name: os.readlink(root / name)
                    for name in sorted(_common.PROVIDER_BINDINGS)
                },
            )

    def test_untracked_index_entries_are_restored_exactly(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repo, root, _provider = _legacy_git_project(parent)
            migrate = _load_migrate_module()
            status_before = _git(repo, "status", "--porcelain")
            listed_before = _git(repo, "ls-files", "-s")

            git_root, tracked, entries = migrate._generated_link_index(root)
            assert git_root is not None
            self.assertEqual(len(_common.PROVIDER_BINDINGS), len(tracked))
            migrate._untrack_generated_links(git_root, tracked)
            self.assertNotEqual(status_before, _git(repo, "status", "--porcelain"))

            migrate._restore_index(git_root, entries)

            self.assertEqual(status_before, _git(repo, "status", "--porcelain"))
            self.assertEqual(listed_before, _git(repo, "ls-files", "-s"))


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import contextlib
import io
import json
import os
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


if __name__ == "__main__":
    unittest.main()

"""Shared helpers for the test-graph skill scripts.

Two roots to keep straight:

1. ``skill_root()`` — the root of THIS repository (where SKILL.md lives).
   Used by scaffold to locate ``project_sdk_sources/`` and by
   ``new-*-node.py`` to locate the templates.

2. ``target_project_root()`` — the scaffolded test_graph project the
   user is currently operating on. Resolution (highest precedence
   first):

       a. ``--test-graph-root`` flag
       b. ``TEST_GRAPH_ROOT`` env var
       c. Walk up from cwd looking for the scaffold markers:
          ``settings.gradle.kts`` plus a ``build.gradle.kts`` containing
          ``validationGraph``. This wins when the user has cd'd into the
          scaffolded project (or any of its subdirs), without mistaking a
          consuming Gradle repository root for the Test Graph root.
       d. Fall back to ``<cwd>/test_graph/`` if it carries both
          ``settings.gradle.kts`` and a ``build.gradle.kts`` that
          mentions ``validationGraph``. This is the "running from the
          project repo root" convenience: a user at
          ``/path/to/myrepo/`` can invoke the scripts without flags
          and have them target ``/path/to/myrepo/test_graph/``.

The two-root split lets one checked-out skill serve many scaffolded
projects without path guessing or per-invocation flags.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path

VALID_KINDS = {"testbed", "fixture", "action", "assertion", "evidence", "report"}

PROVIDER_BINDINGS_SCHEMA = "test-graph.provider-bindings.v1"
PROVIDER_BINDINGS_MANIFEST = "provider-bindings.json"
PROVIDER_BINDINGS = {
    "build-logic": "project_sdk_sources/build-logic",
    "sdk": "project_sdk_sources/sdk",
    "standard-nodes": "project_sdk_sources/standard-nodes",
}
PROVIDER_BINDING_IGNORE_BEGIN = "# TEST-GRAPH-MANAGED-BINDINGS-BEGIN"
PROVIDER_BINDING_IGNORE_END = "# TEST-GRAPH-MANAGED-BINDINGS-END"
_WARNED_LEGACY_BINDING_ROOTS: set[Path] = set()

_GRADLE_MEMORY_GUARDS = (
    "--no-daemon",
    "--max-workers=1",
    "-Pkotlin.compiler.execution.strategy=in-process",
    "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m",
)
_FORBIDDEN_GRADLE_ARG_PREFIXES = (
    "--daemon",
    "--foreground",
    "--max-workers",
    "-Dorg.gradle.daemon",
    "-Dorg.gradle.jvmargs",
    "-Dorg.gradle.workers.max",
    "-Dkotlin.compiler.execution.strategy",
    "-Dkotlin.daemon.jvmargs",
    "-Porg.gradle.daemon",
    "-Porg.gradle.jvmargs",
    "-Porg.gradle.workers.max",
    "-Pkotlin.compiler.execution.strategy",
    "-Pkotlin.daemon.jvmargs",
    "--project-prop=org.gradle.daemon",
    "--project-prop=org.gradle.jvmargs",
    "--project-prop=org.gradle.workers.max",
    "--project-prop=kotlin.compiler.execution.strategy",
    "--project-prop=kotlin.daemon.jvmargs",
    "--system-prop=org.gradle.daemon",
    "--system-prop=org.gradle.jvmargs",
    "--system-prop=org.gradle.workers.max",
    "--system-prop=kotlin.compiler.execution.strategy",
    "--system-prop=kotlin.daemon.jvmargs",
)
_FORBIDDEN_GRADLE_ENV_PREFIXES = (
    "-Dorg.gradle.daemon=",
    "-Dorg.gradle.jvmargs=",
    "-Dorg.gradle.workers.max=",
    "-Dkotlin.compiler.execution.strategy=",
    "-Dkotlin.daemon.jvmargs=",
    "-Porg.gradle.daemon=",
    "-Porg.gradle.jvmargs=",
    "-Porg.gradle.workers.max=",
    "-Pkotlin.compiler.execution.strategy=",
    "-Pkotlin.daemon.jvmargs=",
    "--project-prop=org.gradle.daemon=",
    "--project-prop=org.gradle.jvmargs=",
    "--project-prop=org.gradle.workers.max=",
    "--project-prop=kotlin.compiler.execution.strategy=",
    "--project-prop=kotlin.daemon.jvmargs=",
)
_JVM_OPTION_ENV_VARS = (
    "GRADLE_OPTS",
    "JAVA_OPTS",
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
)
_GRADLE_PROJECT_ENV_GUARDS = {
    "ORG_GRADLE_PROJECT_org.gradle.daemon": "false",
    "ORG_GRADLE_PROJECT_org.gradle.jvmargs": "-Xmx768m -XX:MaxMetaspaceSize=384m",
    "ORG_GRADLE_PROJECT_org.gradle.workers.max": "1",
    "ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy": "in-process",
}
_FORBIDDEN_GRADLE_PROJECT_ENV_VARS = (
    "ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs",
)
_FORBIDDEN_JVM_MEMORY_PREFIXES = (
    "-Xmx",
    "-Xms",
    "-Xmn",
    "-Xss",
    "-XX:MaxMetaspaceSize=",
    "-XX:MetaspaceSize=",
    "-XX:MaxDirectMemorySize=",
    "-XX:ThreadStackSize=",
    "-XX:ReservedCodeCacheSize=",
    "-XX:InitialCodeCacheSize=",
    "-XX:CompressedClassSpaceSize=",
    "-XX:MaxRAM=",
    "-XX:MaxRAMPercentage=",
    "-XX:InitialRAMPercentage=",
    "-XX:MinRAMPercentage=",
    "-XX:MaxRAMFraction=",
    "-XX:InitialRAMFraction=",
    "-XX:MinRAMFraction=",
)
_FORBIDDEN_JVM_OPTION_FILE_PREFIXES = (
    "@",
    "-XX:Flags=",
    "-XX:VMOptionsFile=",
)

# Dotted lowercase segments: app.running, checkout.smoke, user.seeded.v2, ...
_NODE_ID_RE = re.compile(r"^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$")


def skill_root() -> Path:
    """Root of the test-graph skill repo (where SKILL.md lives).

    Layout: <skill-root>/scripts/_common.py
    parents: [0]=scripts/  [1]=<skill-root>
    """
    return Path(__file__).resolve().parents[1]


def project_sdk_sources() -> Path:
    """Template project directory that gets copied on scaffold."""
    return skill_root() / "project_sdk_sources"


def templates_dir() -> Path:
    return skill_root() / "templates"


def target_project_root(override: str | Path | None = None) -> Path:
    """Locate the active scaffolded test_graph project.

    Resolution order:

    1. ``override`` argument (typically ``--test-graph-root`` on a
       script).
    2. ``TEST_GRAPH_ROOT`` environment variable.
    3. Walk up from cwd looking for the complete Test Graph scaffold markers.
    4. Fall back to ``<cwd>/test_graph/`` when it carries
       ``settings.gradle.kts`` AND a ``build.gradle.kts`` containing
       the literal ``validationGraph`` (the DSL entry point); this is
       the "running from the project repo root" shortcut. The
       ``validationGraph`` substring check guards against picking up an
       unrelated ``test_graph`` directory from some other tool.

    Any explicit override (1 or 2) must still point at a directory
    containing ``settings.gradle.kts`` — otherwise we'd silently write
    into a non-test_graph tree.
    """
    if override is None:
        override = os.environ.get("TEST_GRAPH_ROOT")

    if override is not None:
        root = Path(override).expanduser().resolve()
        if not (root / "settings.gradle.kts").is_file():
            sys.exit(
                f"error: --test-graph-root {root} is not a scaffolded test_graph "
                f"project (no settings.gradle.kts)."
            )
        return root

    cwd = Path.cwd().resolve()

    # (3) Walk up — wins when the user is anywhere inside a scaffold. An
    # ordinary consuming Gradle root is not itself a Test Graph root.
    cur = cwd
    while True:
        if _looks_like_test_graph_root(cur):
            return cur
        if cur.parent == cur:
            break
        cur = cur.parent

    # (4) "Running from project repo root" — look for ./test_graph/.
    candidate = cwd / "test_graph"
    if _looks_like_test_graph_root(candidate):
        return candidate

    sys.exit(
        "error: not inside a test_graph project and no scaffolded test_graph/ "
        "found in the current directory.\n"
        "  Scaffold one first with:  "
        f"{skill_root() / 'scripts' / 'scaffold.py'} <repo-root>\n"
        "  Then either cd into <repo-root>/test_graph, run from <repo-root> "
        "directly, or pass --test-graph-root <path> / set TEST_GRAPH_ROOT."
    )


def _looks_like_test_graph_root(candidate: Path) -> bool:
    """True if ``candidate`` is plausibly a scaffolded test_graph project.

    Two cheap signals: ``settings.gradle.kts`` must exist, and
    ``build.gradle.kts`` must mention the ``validationGraph`` DSL
    entry point. The text scan stays a substring match — we don't
    invoke a Gradle parser just to detect the scaffold.
    """
    if not (candidate / "settings.gradle.kts").is_file():
        return False
    bg = candidate / "build.gradle.kts"
    if not bg.is_file():
        return False
    try:
        return "validationGraph" in bg.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return False


def target_sources_dir(override: str | Path | None = None) -> Path:
    """`sources/` inside the active scaffolded project."""
    return target_project_root(override) / "sources"


class ProviderBindingError(RuntimeError):
    """A managed provider binding is invalid or cannot be materialized."""


def provider_bindings_manifest(test_graph_root: Path) -> Path:
    return test_graph_root / PROVIDER_BINDINGS_MANIFEST


def provider_bindings_document(
    workspace_provider: str | None = None,
) -> dict[str, object]:
    candidates: list[dict[str, str]] = []
    if workspace_provider is not None:
        provider_path = Path(workspace_provider)
        if provider_path.is_absolute():
            raise ProviderBindingError(
                "workspace provider must be relative to the test_graph root"
            )
        candidates.append(
            {"kind": "workspace-relative", "path": provider_path.as_posix()}
        )
    candidates.append({"kind": "skill-root"})
    return {
        "schema_version": PROVIDER_BINDINGS_SCHEMA,
        "provider_candidates": candidates,
        "bindings": dict(PROVIDER_BINDINGS),
    }


def write_provider_bindings_manifest(
    test_graph_root: Path,
    *,
    workspace_provider: str | None = None,
) -> Path:
    """Write the strict managed-binding manifest atomically."""

    destination = provider_bindings_manifest(test_graph_root)
    document = provider_bindings_document(workspace_provider)
    payload = json.dumps(document, indent=2, sort_keys=True) + "\n"
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=test_graph_root,
        prefix=f".{PROVIDER_BINDINGS_MANIFEST}.",
        delete=False,
    ) as handle:
        handle.write(payload)
        temporary = Path(handle.name)
    os.replace(temporary, destination)
    return destination


def ensure_provider_binding_ignores(test_graph_root: Path) -> Path:
    """Ignore generated binding paths without disturbing project rules."""

    ignore_path = test_graph_root / ".gitignore"
    existing = ignore_path.read_text(encoding="utf-8") if ignore_path.exists() else ""
    block = "\n".join(
        [
            PROVIDER_BINDING_IGNORE_BEGIN,
            "# Generated runtime links; provider-bindings.json is the durable record.",
            "/build-logic",
            "/sdk",
            "/standard-nodes",
            PROVIDER_BINDING_IGNORE_END,
        ]
    )
    if PROVIDER_BINDING_IGNORE_BEGIN in existing:
        if existing.count(PROVIDER_BINDING_IGNORE_BEGIN) != 1 or existing.count(
            PROVIDER_BINDING_IGNORE_END
        ) != 1:
            raise ProviderBindingError(
                f"managed binding ignore markers are malformed in {ignore_path}"
            )
        before, remainder = existing.split(PROVIDER_BINDING_IGNORE_BEGIN, 1)
        _old, after = remainder.split(PROVIDER_BINDING_IGNORE_END, 1)
        updated = before.rstrip() + "\n\n" + block + after
    else:
        updated = existing.rstrip() + ("\n\n" if existing.strip() else "") + block + "\n"
    ignore_path.write_text(updated, encoding="utf-8")
    return ignore_path


def remove_provider_binding_ignores(test_graph_root: Path) -> None:
    """Remove only the generated ignore block after a scaffold fallback."""

    ignore_path = test_graph_root / ".gitignore"
    if not ignore_path.exists():
        return
    existing = ignore_path.read_text(encoding="utf-8")
    if PROVIDER_BINDING_IGNORE_BEGIN not in existing:
        return
    if existing.count(PROVIDER_BINDING_IGNORE_BEGIN) != 1 or existing.count(
        PROVIDER_BINDING_IGNORE_END
    ) != 1:
        raise ProviderBindingError(
            f"managed binding ignore markers are malformed in {ignore_path}"
        )
    before, remainder = existing.split(PROVIDER_BINDING_IGNORE_BEGIN, 1)
    _old, after = remainder.split(PROVIDER_BINDING_IGNORE_END, 1)
    ignore_path.write_text((before.rstrip() + after).rstrip() + "\n", encoding="utf-8")


def load_provider_bindings(test_graph_root: Path) -> dict[str, object] | None:
    manifest = provider_bindings_manifest(test_graph_root)
    if not manifest.exists():
        return None
    if manifest.is_symlink() or not manifest.is_file():
        raise ProviderBindingError(f"provider binding manifest must be a file: {manifest}")
    if manifest.stat().st_size > 64 * 1024:
        raise ProviderBindingError(f"provider binding manifest is too large: {manifest}")
    try:
        document = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ProviderBindingError(
            f"cannot read provider binding manifest {manifest}: {error}"
        ) from error
    if not isinstance(document, dict):
        raise ProviderBindingError("provider binding manifest must be a JSON object")
    if set(document) != {"schema_version", "provider_candidates", "bindings"}:
        raise ProviderBindingError("provider binding manifest has unknown fields")
    if document.get("schema_version") != PROVIDER_BINDINGS_SCHEMA:
        raise ProviderBindingError("provider binding manifest schema is unsupported")
    if document.get("bindings") != PROVIDER_BINDINGS:
        raise ProviderBindingError("provider binding manifest must declare the exact bindings")
    candidates = document.get("provider_candidates")
    if not isinstance(candidates, list) or not candidates:
        raise ProviderBindingError("provider binding manifest has no provider candidates")
    for candidate in candidates:
        if not isinstance(candidate, dict):
            raise ProviderBindingError("provider candidate must be an object")
        kind = candidate.get("kind")
        if kind == "skill-root":
            if set(candidate) != {"kind"}:
                raise ProviderBindingError("skill-root provider candidate has unknown fields")
        elif kind == "workspace-relative":
            path = candidate.get("path")
            if set(candidate) != {"kind", "path"} or not isinstance(path, str) or not path:
                raise ProviderBindingError("workspace provider candidate is invalid")
            if Path(path).is_absolute():
                raise ProviderBindingError("workspace provider candidate must be relative")
        else:
            raise ProviderBindingError(f"unsupported provider candidate kind: {kind!r}")
    return document


def prepare_provider_bindings(test_graph_root: Path) -> dict[str, object] | None:
    """Materialize a managed scaffold from the first complete provider."""

    root = test_graph_root.resolve()
    document = load_provider_bindings(root)
    if document is None:
        return None
    selected_kind: str | None = None
    selected_root: Path | None = None
    for candidate in document["provider_candidates"]:
        assert isinstance(candidate, dict)
        kind = str(candidate["kind"])
        if kind == "workspace-relative":
            provider_root = (root / str(candidate["path"])).resolve()
        else:
            provider_root = skill_root().resolve()
        if all((provider_root / relative).is_dir() for relative in PROVIDER_BINDINGS.values()):
            selected_kind = kind
            selected_root = provider_root
            break
    if selected_root is None or selected_kind is None:
        raise ProviderBindingError(
            f"no complete Test Graph provider is available for {root}; "
            "install/update the test-graph skill or restore the workspace provider"
        )

    real_paths = [
        root / name
        for name in sorted(PROVIDER_BINDINGS)
        if (root / name).exists() and not (root / name).is_symlink()
    ]
    if real_paths:
        raise ProviderBindingError(
            "managed binding refuses to replace real paths: "
            + ", ".join(str(path) for path in real_paths)
        )

    materialized: dict[str, dict[str, str]] = {}
    for name, provider_relative in sorted(PROVIDER_BINDINGS.items()):
        destination = root / name
        source = (selected_root / provider_relative).resolve(strict=True)
        if selected_kind == "workspace-relative":
            target = os.path.relpath(source, destination.parent)
        else:
            target = str(source)
        if destination.is_symlink():
            current = os.readlink(destination)
            if current == target and destination.resolve(strict=True) == source:
                materialized[name] = {
                    "provider_relative": provider_relative,
                    "target": target,
                }
                continue
            destination.unlink()
        os.symlink(target, destination, target_is_directory=True)
        materialized[name] = {
            "provider_relative": provider_relative,
            "target": target,
        }
    return {
        "schema_version": PROVIDER_BINDINGS_SCHEMA,
        "provider": {"kind": selected_kind, "root": str(selected_root)},
        "bindings": materialized,
    }


def prepare_provider_bindings_or_warn(test_graph_root: Path) -> None:
    """Prepare managed bindings, or notify a legacy scaffold without changing it."""

    root = test_graph_root.resolve()
    try:
        document = load_provider_bindings(root)
        if document is not None:
            prepare_provider_bindings(root)
            return
    except ProviderBindingError as error:
        sys.exit(f"error: {error}")
    legacy_links = [name for name in PROVIDER_BINDINGS if (root / name).is_symlink()]
    if legacy_links and root not in _WARNED_LEGACY_BINDING_ROOTS:
        _WARNED_LEGACY_BINDING_ROOTS.add(root)
        print(
            "notice: legacy Test Graph provider symlinks remain supported; "
            "migrate when ready with:\n"
            f"  {skill_root() / 'scripts' / 'migrate-bindings.py'} "
            f"--test-graph-root {root}\n"
            "  Migration is explicit: it adds a portable provider manifest, "
            "untracks only the three generated links, and leaves copied SDK paths alone.",
            file=sys.stderr,
        )


def add_test_graph_root_arg(parser: argparse.ArgumentParser) -> None:
    """Add the standard ``--test-graph-root`` / ``-R`` flag to a CLI.

    Default is left as ``None`` so :func:`target_project_root` falls
    through to ``TEST_GRAPH_ROOT`` env or auto-detection.
    """
    parser.add_argument(
        "--test-graph-root",
        "-R",
        default=None,
        help="Path to the scaffolded test_graph project. Defaults to "
             "auto-detection: walk up from cwd, then look for ./test_graph/ "
             "in the current directory, then fall back to TEST_GRAPH_ROOT env.",
    )


def validate_node_id(node_id: str) -> None:
    if not _NODE_ID_RE.match(node_id):
        sys.exit(
            f"error: node id must be dotted lowercase segments (got {node_id!r}); "
            f"e.g. 'checkout.smoke'"
        )


def validate_kind(kind: str) -> None:
    if kind not in VALID_KINDS:
        sys.exit(
            f"error: invalid kind {kind!r}; expected one of {sorted(VALID_KINDS)}"
        )


def class_name_from_id(node_id: str) -> str:
    """Camel-case a dotted node id into a Java class name (app.running -> AppRunning)."""
    return "".join(part[:1].upper() + part[1:] for part in node_id.split("."))


def snake_name_from_id(node_id: str) -> str:
    """Snake-case a dotted node id into a Python module name (app.running -> app_running)."""
    return node_id.replace(".", "_")


def render_template(template_path: Path, replacements: dict[str, str]) -> str:
    text = template_path.read_text()
    for key, value in replacements.items():
        text = text.replace(key, value)
    return text


def run_gradle(args: list[str], test_graph_root: str | Path | None = None) -> int:
    """Invoke ``gradlew`` from the active scaffolded project.

    Inherits stdio so the user sees output live. Accepts the same
    ``test_graph_root`` override as :func:`target_project_root`.
    """
    root = target_project_root(test_graph_root)
    prepare_provider_bindings_or_warn(root)
    gradlew = root / "gradlew"
    executable = str(gradlew) if gradlew.exists() else "gradle"
    cmd = [executable, *_bounded_gradle_args(args)]
    env = gradle_env_with_daemon_disabled()
    proc = subprocess.run(cmd, cwd=root, env=env)
    return proc.returncode


def _bounded_gradle_args(args: list[str]) -> list[str]:
    for index, arg in enumerate(args):
        if any(arg == prefix or arg.startswith(f"{prefix}=") for prefix in _FORBIDDEN_GRADLE_ARG_PREFIXES):
            raise ValueError(
                f"run_gradle argument {arg!r} may not override the bounded Gradle/Kotlin runtime"
            )
        if arg in {"-P", "--project-prop", "-D", "--system-prop"} and index + 1 < len(args):
            key = args[index + 1].split("=", 1)[0]
            if key in {
                "org.gradle.daemon",
                "org.gradle.jvmargs",
                "org.gradle.workers.max",
                "kotlin.compiler.execution.strategy",
                "kotlin.daemon.jvmargs",
            }:
                raise ValueError(
                    f"run_gradle argument {arg!r} may not override the bounded Kotlin runtime"
                )
    return [*_GRADLE_MEMORY_GUARDS, *args]


def gradle_env_with_daemon_disabled(env: dict[str, str] | None = None) -> dict[str, str]:
    """Return an env whose Gradle options cannot override bounded execution."""
    merged = os.environ.copy() if env is None else dict(env)
    for name in _JVM_OPTION_ENV_VARS:
        value = merged.get(name, "")
        tokens = shlex.split(value) if value else []
        indirect = next(
            (
                token
                for token in tokens
                if token.startswith(_FORBIDDEN_JVM_OPTION_FILE_PREFIXES)
            ),
            None,
        )
        if indirect is not None:
            raise ValueError(
                f"{name} token {indirect!r} may load JVM options from a file and "
                "bypass the bounded Gradle/Kotlin runtime"
            )
        tokens = [
            token for token in tokens
            if not any(token.startswith(prefix) for prefix in _FORBIDDEN_JVM_MEMORY_PREFIXES)
            and not any(token.startswith(prefix) for prefix in _FORBIDDEN_GRADLE_ENV_PREFIXES)
        ]
        if name == "GRADLE_OPTS":
            tokens.append("-Dorg.gradle.daemon=false")
        if value or tokens:
            merged[name] = shlex.join(tokens)
    for name in _FORBIDDEN_GRADLE_PROJECT_ENV_VARS:
        merged.pop(name, None)
    merged.update(_GRADLE_PROJECT_ENV_GUARDS)
    return merged

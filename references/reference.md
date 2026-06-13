# Reference

Full surface for the test-graph system: NodeSpec, NodeResult, the DSL,
the Gradle tasks, the wire format for `--describe-out` and `--context`,
and the on-disk report layout. Pair with [`../SKILL.md`](../SKILL.md) for
skill routing and [`workflows.md`](workflows.md) for operating workflows.

## NodeSpec (script-declared metadata)

Each script builds a `NodeSpec` and passes it to the SDK runner. The
Gradle plugin calls the script with `--describe-out=<tmp>` and reads the
emitted JSON; no YAML sidecar.

| Field          | Type                                                                        | Required | Notes                                                          |
| -------------- | --------------------------------------------------------------------------- | -------- | -------------------------------------------------------------- |
| `id`           | string (dotted)                                                             | yes      | Stable semantic identifier. Treat as public API.               |
| `kind`         | `testbed` \| `fixture` \| `action` \| `assertion` \| `evidence` \| `report` | yes      | Exactly one.                                                   |
| `runtime`      | `jbang` \| `uv`                                                             | yes      | Fixed by the SDK language (Java -> `jbang`, Python -> `uv`).   |
| `dependsOn`    | list\<string\>                                                              | no       | Upstream node ids.                                             |
| `tags`         | list\<string\>                                                              | no       | Free-form labels.                                              |
| `timeout`      | duration (`30s`, `2m`)                                                      | no       | Default: `60s`.                                                |
| `retries`      | integer                                                                     | no       | Extra executor attempts after timeouts only. Default: 0.       |
| `rerun`        | boolean                                                                     | no       | Default: true. Controls whether failed-run guidance should offer direct rerun from saved input context. |
| `cacheable`    | boolean                                                                     | no       | Default: false. Only true if the node is a pure function of its inputs. |
| `sideEffects`  | list\<string\>                                                              | no       | Typed registry; see side effects below.                        |
| `environmentRepository` | object                                                            | no       | Provider-neutral Git environment repository contract metadata.  |
| `inputs`       | map\<string, type\>                                                         | no       | Typed inputs the node reads from context.                      |
| `outputs`      | map\<string, type\>                                                         | no       | Typed outputs the node produces in its envelope.               |
| `reports`      | object                                                                      | no       | See below.                                                     |

The entry path is not in the spec — the plugin knows it (it's the file it invoked).

### NodeSpec API — Java

```java
NodeSpec.of("login.smoke")
    .kind(NodeSpec.Kind.ASSERTION)
    .dependsOn("app.running", "user.seeded")
    .tags("smoke", "ui")
    .timeout("120s")
    .rerun(false)
    .cacheable(false)
    .sideEffects("browser")
    .sideEffect(SideEffect.env("KUBECONFIG"))
    .environmentRepository(
        EnvironmentRepository.of(
            "git@github.com:example/environments.git",
            "templates/local-preview"))
    .input("baseUrl", "string")
    .output("success", "boolean")
    .junitXml()
    .cucumber();
```

### NodeSpec API — Python

```python
NodeSpec("login.smoke") \
    .kind("assertion") \
    .depends_on("app.running", "user.seeded") \
    .tags("smoke", "ui") \
    .timeout("120s") \
    .rerun(False) \
    .cacheable(False) \
    .side_effects("browser") \
    .side_effects(SideEffect.env("KUBECONFIG")) \
    .environment_repository(
        EnvironmentRepository.of(
            "git@github.com:example/environments.git",
            "templates/local-preview")) \
    .input("baseUrl", "string") \
    .output("success", "boolean") \
    .junit_xml() \
    .cucumber()
```

### Describe mode

Every script accepts `--describe-out=<path>`. In that mode the SDK writes
the spec JSON and exits 0 without running the body. Debug manually:

```bash
jbang sources/LoginSmoke.java  --describe-out=/tmp/spec.json
uv run sources/user_seeded.py  --describe-out=/tmp/spec.json
```

Use `rerun(false)` only when replaying the node from the previous
`context/<node-id>.input.json` would be unsafe: for example, non-idempotent
external mutations, one-shot tokens, claimed ports, or resources that cannot be
reconstructed from the saved context. This is independent of `retries(...)`,
which controls automatic retry after executor timeouts.

### Side effects

`sideEffects` are a typed registry, not arbitrary labels. The Java and Python
SDKs validate them in describe mode, the Gradle DSL validates overlays, and the
executor validates the loaded plan before starting any node subprocess. This
keeps effectful graph behavior auditable and fail-fast.

Current registered forms:

| Form | Meaning |
| --- | --- |
| `browser` | Uses a browser or browser-like UI automation surface. |
| `db:writes` | Writes database or durable fixture state. |
| `fs:tmp` | Writes temporary filesystem state. |
| `net:external` | Calls an external network service. |
| `net:local` | Calls a local service or local cluster endpoint. |
| `process:gradle` | Spawns Gradle or nested test-graph processes. |
| `env:[KEY]` | Requests propagation of one context key as a downstream environment variable. |
| `env:[*]` | Requests propagation of all eligible returned context keys. |
| `environment:provision` | Declares a branch environment provisioning effect. |
| `environment:reuse` | Declares reuse of an existing branch environment. |
| `environment:deploy` | Declares application deployment into a branch environment. |
| `environment:reset` | Declares reset for redeploy without cluster destruction. |
| `environment:destroy` | Declares explicit merge-gated environment destruction. |

TG-5A only validates and carries this metadata. TG-5B adds framework-managed
marker files for `environment:provision`, `environment:reset`, and
`environment:destroy`. This still does not run an environment repository,
OpenTofu, downstream env propagation, deployment, or cleanup beyond marker
state.

Marker state lives under `build/testgraph-provisioning-state/`:

| Directory | Meaning |
| --- | --- |
| `provisioned/<environment-id>.json` | A node with `environment:provision` passed and the branch environment is considered active. |
| `reset/<environment-id>__<run-node>.json` | A reset was requested for redeploy; the provisioned marker remains in place. |
| `destroy-requested/<environment-id>__<run-node>.json` | A destroy node had explicit destroy authorization. |
| `destroyed/<environment-id>.json` | An authorized destroy node passed and the provisioned marker was removed. |

Environment ids are branch-scoped:
`<graph>__<branch>__<target>__<backend>`. The executor derives branch from
`TEST_GRAPH_FEATURE_BRANCH`, `GITHUB_HEAD_REF`, `GITHUB_REF_NAME`, then
`local`. Target defaults to `local-preview`; backend defaults to `local`.
Destroy is refused before node execution unless
`TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true`.

### Environment Repository Contract

`environmentRepository` declares the Git repository contract a later execution
adapter will use for branch-scoped environments. TG-5C validates and carries
this metadata only; it does not clone Git repositories or run OpenTofu.

```json
{
  "environmentRepository": {
    "source": "git@github.com:example/environments.git",
    "template": "templates/local-preview",
    "target": "local-preview",
    "backend": "local",
    "branch": "feature",
    "outputKeys": ["EnvironmentId", "KUBECONFIG", "KUBECONTEXT"]
  }
}
```

Fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `source` | yes | Ordinary Git URL or local Git repository path. Accepted examples include `git@...`, `https://...`, `ssh://...`, `git://...`, `file:///tmp/env-repo`, and local paths. Archive and tarball sources are rejected. |
| `template` | yes | Relative directory inside the environment repo, such as `templates/local-preview`. Absolute paths, empty path segments, `.`, and `..` are rejected. |
| `target` | no | Logical environment target. Defaults to `local-preview`; AWS variants are explicit later tickets. |
| `backend` | no | Execution backend. Defaults to `local`. |
| `branch` | no | Branch scope selector. Defaults to `feature`, meaning the runtime feature branch resolved from `TEST_GRAPH_FEATURE_BRANCH`, `GITHUB_HEAD_REF`, `GITHUB_REF_NAME`, then `local`. |
| `outputKeys` | no | Structured keys returned by the environment repository. Must include `EnvironmentId`, `KUBECONFIG`, and `KUBECONTEXT`. |

Repository layout contract:

```text
<environment-repo>/
  templates/
    local-preview/
      main.tf
      variables.tf
      outputs.tf
```

The standard execution sequence for a later adapter is:

1. Clone or reuse `source` outside the application repository.
2. Check out the selected ref when configured.
3. Enter `template`.
4. Run `tofu init`.
5. Run `tofu apply -auto-approve` for provision/reuse and reset workflows.
6. Read `tofu output -json` and publish at least `EnvironmentId`,
   `KUBECONFIG`, and `KUBECONTEXT`.
7. Run `tofu destroy -auto-approve` only for merge-time destroy when
   `TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT=true`.

Contract tests must not check in a nested Git repository or use a tarball as
the primary fixture. Version ordinary source/template files, then create a real
temporary Git repository with `git init`, `git add`, and `git commit` during
the test that needs one. The SDK contract is deploy-helm neutral; deploy-helm
is one future environment repository implementation, not a dependency here.

This repository's TG-5D fixture source lives under
`test_graph/environment-repository-source/`; the
`generatedEnvironmentRepositoryFixture` graph turns it into a report-local Git
repository and publishes the path, `file://` URL, commit SHA, template path, and
required output keys.

## Gradle DSL

```kotlin
validationGraph {
    sourcesDir("sources")                // pool for transitive dep resolution

    testGraph("smoke") {
        node("sources/user_seeded.py")   // explicit — describe runs at config time

        node("sources/LoginSmoke.java")
            .dependsOn("extra.upstream") // DSL-added dep (unioned with script's)
            .tags("regression")
            .timeout("120s")
            .cacheable(false)
            .sideEffects("net:external")
    }

    testGraph("quick") { … }             // registers another Gradle task
}
```

### NodeOverlay surface

`node(path)` returns a chainable overlay. Every setter returns `this`.

| Method                     | Behavior                                             |
| -------------------------- | ---------------------------------------------------- |
| `.dependsOn(vararg ids)`   | Appends to script's `dependsOn`. Union, not override. |
| `.tags(vararg t)`          | Appends to script's tags.                            |
| `.sideEffects(vararg s)`   | Appends to script's sideEffects.                    |
| `.timeout(v)`              | Overrides the scalar timeout.                        |
| `.cacheable(b)`            | Overrides the scalar cacheable flag.                |

**Merge policy**: collections are unioned; scalars override if set. The script is always the floor — the DSL can only add edges or tighten constraints, never hide a dep the script declared.

## Toolchain (jbang + uv)

The plugin resolves both runtime binaries at task-execution time and logs the choice:

```
[toolchain] jbang=0.137.0 (path: /opt/homebrew/bin/jbang)
[toolchain] uv=0.7.2 (path: /opt/homebrew/bin/uv)
```

Resolution rules per tool:

1. If `override-<tool>-version` is set AND the version on `PATH` matches it, use PATH.
2. If override is set AND PATH differs, or the tool is not on PATH, download the override version to `<project>/.bin/`.
3. With no override and tool on PATH, use PATH.
4. With no override and no tool on PATH, download the default version.

Tasks invoke the tools by **absolute path**, so a resolved binary always takes precedence over `PATH` when the plugin drives it.

### Gradle properties

| Property                     | Default   | Effect                                             |
| ---------------------------- | --------- | -------------------------------------------------- |
| `override-jbang-version`     | `0.137.0` | Pin the jbang version (download if PATH differs).  |
| `override-uv-version`        | `0.7.2`   | Pin the uv version (download if PATH differs).     |

Pass on the CLI:

```bash
./gradlew smoke -Poverride-jbang-version=0.118.0 -Poverride-uv-version=0.6.0
```

Or put in `gradle.properties` at the scaffolded project root:

```properties
override-jbang-version=0.118.0
override-uv-version=0.6.0
```

### Cache layout

Downloaded binaries live under the project root (gitignored):

```
<test_graph>/.bin/
  jbang-<version>/bin/jbang
  uv-<version>/uv
```

Delete `.bin/` to force redownload. Defaults are bumped by editing `Toolchain.DEFAULT_JBANG_VERSION` / `DEFAULT_UV_VERSION` in `build-logic/`.

## Gradle tasks

Agents should normally use the wrapper scripts from [`workflows.md`](workflows.md).
These are the underlying Gradle tasks.

| Task                                    | Purpose                                              |
| --------------------------------------- | ---------------------------------------------------- |
| `<graphName>`                           | Run the test graph with that name.                   |
| `validationRunAll`                      | Run every registered graph serially in declaration order. |
| `validationListGraphs`                  | List all registered graphs + their explicit nodes.   |
| `validationPlanGraph --name=<graph>`    | Plan (topo table + dependency adjacency).            |
| `validationGraphDot --name=<graph>`     | Emit graphviz DOT only (pipe-friendly).              |
| `validationReport`                      | Re-render summary.json + report.md for every existing run dir (manual rebuild — graph tasks already write their own rollup inline). |

`discover.py <graph>` wraps `validationPlanGraph` (for the human console output) and `validationGraphDot` (for `docs/<graph>.dot`), and renders `docs/<graph>.png` if `dot` is on PATH.

`run.py --all` wraps `validationRunAll`.

Graph tasks accept resume options for a single graph:

| Option | Purpose |
| --- | --- |
| `--resume-from-build=<dir>` | Existing `build/validation-reports/<runId>` directory containing saved input contexts. |
| `--resume-from-node=<node-id>` | Node id whose `context/<node-id>.input.json` seeds a resumed run that continues downstream. |
| `--run-only-node=<node-id>` | Node id whose `context/<node-id>.input.json` seeds a single-node replay that does not continue downstream. |

Use `--resume-from-build` with exactly one node selector:
`--resume-from-node` or `--run-only-node`. The selected node must be in the
graph plan, must have `rerun=true`, and its saved input context must contain all
of its declared dependencies. Resume mode skips earlier plan steps and continues
from the selected node through the rest of the graph. Run-only mode executes
only the selected node. Both modes write refreshed envelope and report files
back into the same build directory.

When a rerunnable node finishes with `failed` or `errored`, the canonical
envelope may include a `rerunGuidance` object:

```json
{
  "rerunGuidance": {
    "resumeGraphCommand": "./gradlew smoke --resume-from-build '/path/to/build/validation-reports/<runId>' --resume-from-node 'login.smoke'",
    "runOnlyCommand": "./gradlew smoke --resume-from-build '/path/to/build/validation-reports/<runId>' --run-only-node 'login.smoke'",
    "inputContextFile": "context/login.smoke.input.json"
  }
}
```

`report.md` renders the same commands under **Rerun guidance**. Nodes with
`rerun(false)` suppress this guidance even when they fail.

Wrapper forms:

```bash
<skill>/scripts/run.py smoke \
  --resume-from-build <test_graph>/build/validation-reports/<runId> \
  --resume-from-node login.smoke

<skill>/scripts/run.py smoke \
  --resume-from-build <test_graph>/build/validation-reports/<runId> \
  --run-only-node login.smoke
```

## Unified result envelope

What each node writes to `build/validation-reports/<runId>/envelope/<nodeId>.json`:

```json
{
  "nodeId": "login.smoke",
  "status": "passed",
  "startedAt": "2026-04-21T22:06:57.043351Z",
  "endedAt":   "2026-04-21T22:06:57.216374Z",
  "assertions": [
    { "name": "login_endpoint_reachable", "status": "passed" }
  ],
  "artifacts": [
    { "type": "screenshot", "path": "build/validation-reports/20260421-220657/login.png" }
  ],
  "metrics": { "statusCode": 200, "durationMs": 173 },
  "logs": [],
  "published": { "attemptedAs": "u-1a2b3c4d" }
}
```

- `status`: `passed | failed | errored | skipped`.
- `published` is this node's contribution to the downstream `Context[]`.

## Context[] — the data wire between nodes

### Wire format

One CLI arg, two encodings:

- **Inline** (≤ 8 KB): `--context={"items":[{"nodeId":"user.seeded","data":{"userId":"u-1a2b"}}, ...]}`
- **File ref**: `--context=@<abs-path>` — every attempted node has its exact input context saved at `<reportDir>/context/<node-id>.input.json`; large runtime args may also spill to `<reportDir>/context/step-NNN.json`.

### Shape

```json
{
  "items": [
    { "nodeId": "app.running", "data": { "baseUrl": "http://localhost:8080" } },
    { "nodeId": "user.seeded", "data": { "userId": "u-1a2b3c4d", "username": "smoke-user" } }
  ]
}
```

Order reflects plan execution order. Exactly one `ContextItem` per upstream node in the plan.

### Reading — Java

```java
String userId = ctx.get("user.seeded", "userId").orElseThrow();
ContextItem it = ctx.item("user.seeded").orElseThrow();
List<ContextItem> all = ctx.context();
```

### Reading — Python

```python
user_id = ctx.get("user.seeded", "userId")
item    = ctx.item("user.seeded")
for it in ctx.context:
    ...
```

### Publishing

Call `NodeResult.publish(key, value)`. Everything in `published` is forwarded automatically. `logs`, `metrics`, `artifacts` do NOT flow downstream — they're report-only. For upstream envelope access beyond primitives, use `ctx.upstream(id)`.

## Importing user code

Node scripts can pull in source files from the user's own codebase when
this scaffold lives at `<user-project>/test_graph/`. From
`test_graph/sources/<script>`, the user-project root is always `../..`.

### Java (JBang)

- `//SOURCES ../../src/main/java/com/acme/**/*.java` — compile user classes alongside the node.
- `//DEPS <group:artifact:version>` — resolve external JVM libraries from Maven Central.

Paths are relative to the node script file. Be specific to keep compilation fast.

### Python (uv)

Inline script metadata supports `[tool.uv.sources]` with path-based sources:

```
# dependencies = ["testgraphsdk", "acme-domain"]
#
# [tool.uv.sources]
# testgraphsdk = { path = "../sdk/python", editable = true }
# acme-domain          = { path = "../..",          editable = true }
```

Assumes `<user-project>/pyproject.toml` declares the package. `sys.path` insertion at `Path(__file__).resolve().parents[2] / "src" / "main" / "python"` is the fallback for unpackaged user code.

See [`workflows.md`](workflows.md) -> **Import User Code** for full examples.

## Report output

Each run writes under `build/validation-reports/<runId>/`:

```
build/validation-reports/<runId>/
  envelope/<nodeId>.json     # canonical per-node envelope
  context/<nodeId>.input.json # exact input Context[] for that node attempt
  context/step-NNN.json      # optional large runtime --context spill file
  summary.json               # aggregated summary (written inline at end of run)
  report.md                  # markdown rollup (same)
```

`summary.json` is the machine-readable handoff for CI, dashboards, agents.

## Java SDK (`com.hayden.testgraphsdk.sdk`)

### Node.run

```java
Node.run(args, spec, ctx -> { … return NodeResult; });
```

- If `--describe-out=<path>` is present: serialize spec, exit 0.
- Otherwise: parse context, run body, write envelope, exit pass/fail.

### NodeContext members

| Member               | Returns                 | Purpose                                           |
| -------------------- | ----------------------- | ------------------------------------------------- |
| `nodeId()`           | `String`                | Currently executing node id.                      |
| `input(key)`         | `Optional<String>`      | Typed input from the graph model.                 |
| `reportDir()`        | `Path`                  | Where to write artifacts for this run.            |
| `runId()`            | `String`                | Id of the overall graph run.                      |
| `context()`          | `List<ContextItem>`     | Ordered upstream Context[] (execution order).     |
| `get(upId, key)`     | `Optional<String>`      | Lookup from an upstream node's published data.    |
| `item(upId)`         | `Optional<ContextItem>` | Full ContextItem for one upstream node.           |

### NodeResult methods

| Method                        | Purpose                                      |
| ----------------------------- | -------------------------------------------- |
| `NodeResult.pass(nodeId)`     | Start a passed envelope.                    |
| `NodeResult.fail(nodeId,msg)` | Start a failed envelope.                    |
| `NodeResult.error(nodeId,t)`  | Start an errored envelope from a throwable. |
| `.assertion(name, bool)`      | Record a named assertion.                    |
| `.artifact(type, path)`       | Register an artifact path.                  |
| `.metric(name, number)`       | Add a numeric metric.                       |
| `.log(line)`                  | Add a log line (report-only).               |
| `.publish(key, value)`        | Publish to downstream `Context[]`.          |
| `.toContextItem()`            | Project published map as ContextItem.       |

## Python SDK (`testgraphsdk`)

### @node decorator

```python
@node(spec)
def main(ctx):
    …
    return NodeResult.pass_(spec.id)

if __name__ == "__main__":
    main()
```

### NodeContext attributes

| Attribute               | Returns                | Purpose                                             |
| ----------------------- | ---------------------- | --------------------------------------------------- |
| `ctx.node_id`           | `str`                  | Currently executing node id.                        |
| `ctx.input(key)`        | `str \| None`          | Typed input.                                        |
| `ctx.report_dir`        | `pathlib.Path`         | Where to write artifacts.                           |
| `ctx.run_id`            | `str`                  | Overall run id.                                     |
| `ctx.context`           | `list[ContextItem]`    | Ordered upstream Context[].                         |
| `ctx.get(up_id, key)`   | `str \| None`          | Lookup upstream published data.                     |
| `ctx.item(up_id)`       | `ContextItem \| None`  | Full ContextItem for one upstream node.             |
| `ctx.upstream(node_id)` | `dict \| None`         | Full envelope JSON for an upstream node.            |

### NodeResult methods

| Method                             | Purpose                        |
| ---------------------------------- | ------------------------------ |
| `NodeResult.pass_(node_id)`        | Passed envelope.               |
| `NodeResult.fail(node_id, msg)`    | Failed envelope.               |
| `NodeResult.error(node_id, exc)`   | Errored envelope.              |
| `.assertion(name, ok)`             | Named assertion.               |
| `.artifact(kind, path)`            | File artifact.                 |
| `.metric(name, value)`             | Numeric metric.                |
| `.log(line)`                       | Log line (report-only).        |
| `.publish(key, value)`             | Publish to downstream.         |
| `.to_context_item()`               | Project published as ContextItem. |

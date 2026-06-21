package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationNodeSpec
import java.io.File
import java.time.Instant

internal data class BranchEnvironmentIdentity(
    val graph: String,
    val branch: String,
    val target: String,
    val backend: String,
) {
    val id: String = listOf(graph, branch, target, backend)
        .joinToString("__") { safeSegment(it) }

    companion object {
        fun safeSegment(value: String): String =
            value.trim()
                .ifEmpty { "default" }
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('.', '-', '_')
                .ifEmpty { "default" }
    }
}

internal data class PreparedProvisioningState(
    val identity: BranchEnvironmentIdentity,
    val actions: Set<String>,
    val environment: Map<String, String>,
)

internal data class ProvisioningStateRecord(
    val identity: BranchEnvironmentIdentity,
    val actions: Set<String>,
    val provisionedMarker: File?,
    val resetMarker: File?,
    val destroyRequestMarker: File?,
    val destroyedMarker: File?,
)

/**
 * Framework-managed marker files for branch environment lifecycle visibility.
 *
 * TG-5B deliberately records lifecycle state only. Concrete environment
 * repositories and OpenTofu execution are later adapters that must preserve
 * this marker contract.
 */
internal class ProvisioningState(
    projectDir: File,
    private val graphName: String,
    private val runId: String,
    private val env: Map<String, String> = System.getenv(),
) {
    val stateRoot: File = File(projectDir, "build/testgraph-provisioning-state")

    fun prepare(spec: ValidationNodeSpec): PreparedProvisioningState? {
        val actions = environmentActions(spec)
        if (actions.isEmpty()) return null

        val identity = identity(spec)
        if (identity.requiresAwsCredentials()) {
            require(awsCredentialsPresent()) {
                "node '${spec.id}' selected AWS branch environment target/backend " +
                    "(${identity.target}/${identity.backend}) but AWS credentials were not found"
            }
        }
        if ("destroy" in actions) {
            require(destroyRequested()) {
                "node '${spec.id}' declares environment:destroy but TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT is not true"
            }
        }

        val vars = linkedMapOf(
            "TEST_GRAPH_PROVISIONING_STATE_DIR" to stateRoot.absolutePath,
            "TEST_GRAPH_BRANCH_ENVIRONMENT_ID" to identity.id,
            "TEST_GRAPH_FEATURE_BRANCH" to identity.branch,
            "TEST_GRAPH_ENVIRONMENT_TARGET" to identity.target,
            "TEST_GRAPH_ENVIRONMENT_BACKEND" to identity.backend,
        )
        return PreparedProvisioningState(identity, actions, vars)
    }

    fun recordSuccessful(
        spec: ValidationNodeSpec,
        prepared: PreparedProvisioningState?,
        status: String,
    ): ProvisioningStateRecord? {
        if (prepared == null || status != "passed") return null

        val identity = prepared.identity
        val timestamp = Instant.now().toString()
        val provisionedMarker = if ("provision" in prepared.actions) {
            marker("provisioned", identity.id).also {
                writeMarker(it, identity, spec, timestamp, "provisioned")
            }
        } else null

        val resetMarker = if ("reset" in prepared.actions) {
            marker("reset", "${identity.id}__${safeRunNode(spec)}").also {
                writeMarker(it, identity, spec, timestamp, "reset")
            }
        } else null

        val destroyRequestMarker = if ("destroy" in prepared.actions) {
            marker("destroy-requested", "${identity.id}__${safeRunNode(spec)}").also {
                writeMarker(it, identity, spec, timestamp, "destroy-requested")
            }
        } else null

        val destroyedMarker = if ("destroy" in prepared.actions) {
            marker("destroyed", identity.id).also {
                writeMarker(it, identity, spec, timestamp, "destroyed")
                marker("provisioned", identity.id).delete()
            }
        } else null

        return ProvisioningStateRecord(
            identity = identity,
            actions = prepared.actions,
            provisionedMarker = provisionedMarker,
            resetMarker = resetMarker,
            destroyRequestMarker = destroyRequestMarker,
            destroyedMarker = destroyedMarker,
        )
    }

    fun isProvisioned(identity: BranchEnvironmentIdentity): Boolean =
        markerFile("provisioned", identity.id).isFile

    private fun environmentActions(spec: ValidationNodeSpec): Set<String> =
        spec.sideEffectSpecs()
            .filter { it.family == "environment" }
            .mapNotNullTo(linkedSetOf()) { it.action }

    private fun identity(spec: ValidationNodeSpec): BranchEnvironmentIdentity {
        val repository = spec.environmentRepository
        val branchSelector = repository?.branch?.trim()
        val branch = firstEnv("TEST_GRAPH_FEATURE_BRANCH", "GITHUB_HEAD_REF", "GITHUB_REF_NAME")
            ?: branchSelector?.takeIf { it.isNotEmpty() && it != "feature" }
            ?: "local"

        return BranchEnvironmentIdentity(
            graph = graphName,
            branch = branch,
            target = firstEnv("TEST_GRAPH_ENVIRONMENT_TARGET")
                ?: repository?.target
                ?: "local-preview",
            backend = firstEnv("TEST_GRAPH_ENVIRONMENT_BACKEND")
                ?: repository?.backend
                ?: "local",
        )
    }

    private fun firstEnv(vararg keys: String): String? =
        keys.asSequence()
            .mapNotNull { env[it]?.trim() }
            .firstOrNull { it.isNotEmpty() }

    private fun destroyRequested(): Boolean =
        env["TEST_GRAPH_DESTROY_BRANCH_ENVIRONMENT"]?.trim()?.lowercase() in
            setOf("1", "true", "yes", "y")

    private fun awsCredentialsPresent(): Boolean =
        firstEnv("AWS_PROFILE", "AWS_ACCESS_KEY_ID", "AWS_WEB_IDENTITY_TOKEN_FILE") != null

    private fun BranchEnvironmentIdentity.requiresAwsCredentials(): Boolean =
        target.contains("aws", ignoreCase = true) || backend.contains("aws", ignoreCase = true)

    private fun marker(kind: String, name: String): File =
        markerFile(kind, name).also { it.parentFile.mkdirs() }

    private fun markerFile(kind: String, name: String): File =
        File(File(stateRoot, kind), "$name.json")

    private fun safeRunNode(spec: ValidationNodeSpec): String =
        BranchEnvironmentIdentity.safeSegment("${runId}__${spec.id}")

    private fun writeMarker(
        file: File,
        identity: BranchEnvironmentIdentity,
        spec: ValidationNodeSpec,
        timestamp: String,
        state: String,
    ) {
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                append("{\n")
                append("  \"environmentId\": ").append(json(identity.id)).append(",\n")
                append("  \"graph\": ").append(json(identity.graph)).append(",\n")
                append("  \"branch\": ").append(json(identity.branch)).append(",\n")
                append("  \"target\": ").append(json(identity.target)).append(",\n")
                append("  \"backend\": ").append(json(identity.backend)).append(",\n")
                append("  \"state\": ").append(json(state)).append(",\n")
                append("  \"nodeId\": ").append(json(spec.id)).append(",\n")
                append("  \"runId\": ").append(json(runId)).append(",\n")
                append("  \"updatedAt\": ").append(json(timestamp)).append("\n")
                append("}\n")
            }
        )
    }

    private fun json(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
}

package com.hayden.testgraphsdk

private val VALID_NODE_ID = Regex("[a-z0-9._-]{1,128}")

internal fun isValidNodeId(nodeId: String): Boolean = VALID_NODE_ID.matches(nodeId)

/**
 * The grammar is intentional and documented; this is about what the REFUSAL
 * says when a script breaks it.
 *
 * It used to say only `node id must match [a-z0-9._-]{1,128}` — no rejected
 * value, no source. In a graph of one node that is enough; in a graph of
 * twenty it names nothing, and the reader has to open every script to find the
 * one that is wrong. Measured cost: real diagnosis time, and on one occasion a
 * wrong root cause that survived unchallenged because the message pointed
 * nowhere.
 *
 * So the message carries the two things the reader is about to go looking for:
 * the value that was rejected, and — when the caller knows it — the file that
 * declared it. [renderRejectedNodeId] keeps that value readable: a trailing
 * newline or a control character is exactly the kind of id that gets rejected,
 * and pasting it raw into an error message hides the defect it is reporting.
 */
internal fun requireValidNodeId(
    nodeId: String,
    label: String = "node id",
    source: String? = null,
): String {
    require(isValidNodeId(nodeId)) {
        buildString {
            append(label)
            append(" must match [a-z0-9._-]{1,128}, but was ")
            append(renderRejectedNodeId(nodeId))
            if (!source.isNullOrBlank()) {
                append(" (declared by ")
                append(source)
                append(")")
            }
        }
    }
    return nodeId
}

/** Longest rejected id rendered in full before it is elided. */
private const val MAX_RENDERED_NODE_ID = 160

/**
 * A rejected id, quoted, with invisible characters escaped and the true length
 * stated. The length matters on its own: an id that is over 128 characters
 * looks perfectly well-formed, so "but was <160 legible characters>" without a
 * count reads as if the grammar itself were wrong.
 */
private fun renderRejectedNodeId(nodeId: String): String {
    val escaped = buildString {
        for (ch in nodeId) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.isISOControl() -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
    }
    val shown =
        if (escaped.length <= MAX_RENDERED_NODE_ID) escaped
        else escaped.take(MAX_RENDERED_NODE_ID) + "..."
    return "\"$shown\" (length ${nodeId.length})"
}

/**
 * Typed graph-model representation of a node.
 *
 * Self-describe output from each script gets parsed into one of these,
 * then (for explicit nodes) merged with any DSL overlay. This is the
 * single source of truth the plugin tasks act on.
 */
data class ValidationNodeSpec(
    val id: String,
    val kind: NodeKind,
    val runtime: ValidationRuntime,
    val dependsOn: List<String> = emptyList(),
    val tags: Set<String> = emptySet(),
    val timeout: String = "60s",
    /**
     * Extra attempts the executor makes when the spawned node-process
     * exceeds its [timeout]. Default 0 means "fail fast on first timeout".
     * Retries trigger ONLY on a timeout outcome — a body-returned
     * `failed`/`errored` is final on the first attempt. Authors opt in
     * per node via `NodeSpec.retries(n)` (or the DSL overlay's
     * `.retries(n)`); most nodes are stateful and not safely re-runnable.
     */
    val retries: Int = 0,
    /**
     * Whether failed-run output should suggest a direct rerun command from the
     * saved build-directory input context. Defaults true and is independent of
     * timeout [retries], which are automatic executor attempts.
     */
    val rerun: Boolean = true,
    val cacheable: Boolean = false,
    val sideEffects: Set<String> = emptySet(),
    val environmentRepository: EnvironmentRepositorySpec? = null,
    val inputs: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
    val reports: ReportsSpec = ReportsSpec(),
) {
    init {
        // The runtime already carries the script this spec was described from,
        // so the refusal can name the file to open. This is the case that costs
        // the most to diagnose without it: `indexDir` describes every script in
        // a directory, and the failure surfaces with no indication of which one.
        requireValidNodeId(id, source = runtime.entryFile)
    }

    fun sideEffectSpecs(): Set<SideEffectSpec> =
        SideEffectSpec.parseAll(sideEffects, "node '$id' sideEffects")
}

internal fun ValidationNodeSpec.isFinalizerNode(): Boolean =
    id.endsWith(".cleanup") || "finalizer" in tags

data class ReportsSpec(
    val structuredJson: Boolean = true,
    val junitXml: Boolean = false,
    val cucumber: Boolean = false,
)

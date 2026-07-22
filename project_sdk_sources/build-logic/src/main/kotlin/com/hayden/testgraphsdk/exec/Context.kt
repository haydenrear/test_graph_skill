package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.requireValidNodeId
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Plugin-side mirror of the SDK ContextItem. */
data class ContextItem(val nodeId: String, val data: Map<String, String>) {
    init {
        requireValidNodeId(nodeId, "context nodeId")
    }
}

/**
 * Threshold (in characters of serialized JSON) below which the Context[]
 * rides inline as the --context arg. Above it, we spill to a file in
 * reportDir and pass --context=@<path>.
 *
 * 8 KB is well under the common ARG_MAX headroom while still letting
 * small graphs avoid a file write per step.
 */
internal const val CONTEXT_INLINE_LIMIT = 8 * 1024
internal const val CONTEXT_JSON_MAX_UTF8_BYTES = 16 * 1024 * 1024
internal const val CONTEXT_JSON_MAX_DEPTH = 64
internal const val CONTEXT_JSON_MAX_STRUCTURAL_TOKENS = 500_000
internal const val CONTEXT_JSON_MAX_STRING_CHARS = 8 * 1024 * 1024

object ContextSerde {

    fun toJson(items: List<ContextItem>): String {
        val serializedUtf8Bytes = serializedContextUtf8Bytes(items)
        val sb = StringBuilder(serializedUtf8Bytes)
        sb.append("{\"items\":[")
        for ((i, it) in items.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"nodeId\":").append(quote(it.nodeId))
            sb.append(",\"data\":{")
            var k = 0
            for ((key, value) in it.data) {
                if (k++ > 0) sb.append(',')
                sb.append(quote(key)).append(':').append(quote(value))
            }
            sb.append("}}")
        }
        sb.append("]}")
        val json = sb.toString()
        // Keep writer and reader contracts identical, including the structural
        // token limit as well as the precomputed byte cap.
        validateBoundedJson(json, "serialized context")
        return json
    }

    /**
     * Parse the Context[] wire format emitted by [toJson]. This is used by
     * build-directory resume so the executor can seed a run from the exact
     * input context snapshot captured before the selected node's prior attempt.
     */
    fun fromJson(json: String): List<ContextItem> {
        return contextItems(parseBoundedJsonObject(json, "context"))
    }

    internal fun fromJson(file: File): List<ContextItem> {
        val (_, root) = readBoundedJsonObject(file, "context file ${file.name}")
        return contextItems(root)
    }

    private fun contextItems(root: Map<String, Any?>): List<ContextItem> {
        val rawItems = root["items"] as? List<*>
            ?: throw IllegalArgumentException("context items must be a JSON array")
        val seenNodeIds = mutableSetOf<String>()
        return rawItems.mapIndexed { index, raw ->
            val obj = raw as? Map<*, *>
                ?: throw IllegalArgumentException("context item $index must be a JSON object")
            val nodeId = obj["nodeId"] as? String
                ?: throw IllegalArgumentException("context item $index must have a string nodeId")
            if (!seenNodeIds.add(nodeId)) {
                throw IllegalArgumentException("context contains duplicate nodeId '$nodeId'")
            }
            ContextItem(nodeId, strictStringMap(obj["data"], "context item $index data"))
        }
    }

    /**
     * Extract the `published` block from a node envelope as an exact flat
     * string/string map. A non-recursive, JSON-string-aware preflight bounds
     * input and the existing parser's maximum call depth before it retains any
     * object/list. Malformed, oversized, over-deep, or non-string published
     * data fails closed instead of producing partial downstream context.
     */
    fun extractPublished(envelopeJson: String): Map<String, String> {
        val root = parseBoundedJsonObject(envelopeJson, "node envelope")
        return published(root)
    }

    internal fun extractPublished(envelopeFile: File): Map<String, String> {
        val (_, root) = readBoundedJsonObject(
            envelopeFile,
            "node envelope ${envelopeFile.name}",
        )
        return published(root)
    }

    private fun published(root: Map<String, Any?>): Map<String, String> {
        if (!root.containsKey("published")) return emptyMap()
        return strictStringMap(root["published"], "published")
    }

    private fun quote(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c.code < 0x20) b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }

}

private fun serializedContextUtf8Bytes(items: List<ContextItem>): Int {
    var total = 0L
    fun add(bytes: Long) {
        if (bytes < 0 || total > CONTEXT_JSON_MAX_UTF8_BYTES.toLong() - bytes) {
            throw IllegalArgumentException(
                "serialized context exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes"
            )
        }
        total += bytes
    }

    add("{\"items\":[".length.toLong())
    for ((itemIndex, item) in items.withIndex()) {
        if (itemIndex > 0) add(1)
        add("{\"nodeId\":".length.toLong())
        add(quotedJsonUtf8Bytes(item.nodeId))
        add(",\"data\":{".length.toLong())
        for ((entryIndex, entry) in item.data.entries.withIndex()) {
            if (entryIndex > 0) add(1)
            add(quotedJsonUtf8Bytes(entry.key))
            add(1)
            add(quotedJsonUtf8Bytes(entry.value))
        }
        add(2)
    }
    add(2)
    return total.toInt()
}

private fun quotedJsonUtf8Bytes(value: String): Long {
    var bytes = 2L
    var index = 0
    while (index < value.length) {
        val ch = value[index++]
        bytes += when {
            ch == '"' || ch == '\\' || ch == '\n' || ch == '\r' || ch == '\t' -> 2
            ch.code < 0x20 -> 6
            ch.code <= 0x7f -> 1
            ch.code <= 0x7ff -> 2
            ch.isHighSurrogate() -> {
                if (index >= value.length || !value[index].isLowSurrogate()) {
                    throw IllegalArgumentException("context string contains an unpaired high surrogate")
                }
                index++
                4
            }
            ch.isLowSurrogate() ->
                throw IllegalArgumentException("context string contains an unpaired low surrogate")
            else -> 3
        }
        if (bytes > CONTEXT_JSON_MAX_UTF8_BYTES) {
            throw IllegalArgumentException(
                "serialized context exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes"
            )
        }
    }
    return bytes
}

internal fun parseBoundedJsonObject(json: String, label: String): Map<String, Any?> {
    validateBoundedJson(json, label)
    val parsed = MiniJson.parse(json)
    @Suppress("UNCHECKED_CAST")
    return parsed as? Map<String, Any?>
        ?: throw IllegalArgumentException("$label must be a JSON object")
}

/** Read a UTF-8 JSON file without ever retaining more than the boundary cap. */
internal fun readBoundedJsonObject(
    file: File,
    label: String,
    maxUtf8Bytes: Int = CONTEXT_JSON_MAX_UTF8_BYTES,
): Pair<String, Map<String, Any?>> {
    require(maxUtf8Bytes in 1..CONTEXT_JSON_MAX_UTF8_BYTES) {
        "JSON byte limit must be between 1 and $CONTEXT_JSON_MAX_UTF8_BYTES"
    }
    if (!file.isFile) throw IllegalArgumentException("$label is not a file")
    if (file.length() > maxUtf8Bytes) {
        throw IllegalArgumentException("$label exceeds $maxUtf8Bytes UTF-8 bytes")
    }
    val bytes = file.inputStream().use { input ->
        input.readNBytes(maxUtf8Bytes + 1)
    }
    if (bytes.size > maxUtf8Bytes) {
        throw IllegalArgumentException("$label exceeds $maxUtf8Bytes UTF-8 bytes")
    }
    val json = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    return json to parseBoundedJsonObject(json, label)
}

private fun strictStringMap(raw: Any?, label: String): Map<String, String> {
    val map = raw as? Map<*, *>
        ?: throw IllegalArgumentException("$label must be a JSON object")
    val out = linkedMapOf<String, String>()
    for ((key, value) in map) {
        if (key !is String || value !is String) {
            throw IllegalArgumentException("$label must contain only string/string entries")
        }
        out[key] = value
    }
    return out
}

/**
 * Non-recursive, string-aware guard in front of [MiniJson]. It bounds the
 * parser's eventual call depth and retained input before any object/list is
 * allocated, and rejects malformed delimiter/string structure fail-closed.
 */
private fun validateBoundedJson(json: String, label: String) {
    boundedUtf8Length(json, label)
    val delimiters = java.util.ArrayDeque<Char>()
    var structuralTokens = 0
    var stringChars = 0
    var inString = false
    var index = 0
    while (index < json.length) {
        val ch = json[index++]
        if (inString) {
            when (ch) {
                '"' -> {
                    inString = false
                    stringChars = 0
                }
                '\\' -> {
                    if (index >= json.length) {
                        throw IllegalArgumentException("$label has an unterminated string escape")
                    }
                    when (json[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            if (index + 4 > json.length) {
                                throw IllegalArgumentException("$label has an incomplete unicode escape")
                            }
                            repeat(4) {
                                if (json[index++].digitToIntOrNull(16) == null) {
                                    throw IllegalArgumentException("$label has an invalid unicode escape")
                                }
                            }
                        }
                        else -> throw IllegalArgumentException("$label has an invalid string escape")
                    }
                    stringChars += 2
                }
                else -> {
                    if (ch.code < 0x20) {
                        throw IllegalArgumentException("$label has an unescaped control character")
                    }
                    stringChars++
                }
            }
            if (stringChars > CONTEXT_JSON_MAX_STRING_CHARS) {
                throw IllegalArgumentException(
                    "$label string exceeds $CONTEXT_JSON_MAX_STRING_CHARS characters"
                )
            }
            continue
        }

        when (ch) {
            '"' -> {
                inString = true
                stringChars = 0
            }
            '{', '[' -> {
                structuralTokens++
                delimiters.addLast(ch)
                if (delimiters.size > CONTEXT_JSON_MAX_DEPTH) {
                    throw IllegalArgumentException(
                        "$label nesting exceeds $CONTEXT_JSON_MAX_DEPTH"
                    )
                }
            }
            '}' -> {
                structuralTokens++
                if (delimiters.pollLast() != '{') {
                    throw IllegalArgumentException("$label has mismatched object delimiters")
                }
            }
            ']' -> {
                structuralTokens++
                if (delimiters.pollLast() != '[') {
                    throw IllegalArgumentException("$label has mismatched array delimiters")
                }
            }
            ',', ':' -> structuralTokens++
        }
        if (structuralTokens > CONTEXT_JSON_MAX_STRUCTURAL_TOKENS) {
            throw IllegalArgumentException(
                "$label exceeds $CONTEXT_JSON_MAX_STRUCTURAL_TOKENS structural tokens"
            )
        }
    }
    if (inString) throw IllegalArgumentException("$label has an unterminated string")
    if (delimiters.isNotEmpty()) throw IllegalArgumentException("$label has unclosed delimiters")
}

private fun boundedUtf8Length(value: String, label: String) {
    if (value.length > CONTEXT_JSON_MAX_UTF8_BYTES) {
        throw IllegalArgumentException("$label exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes")
    }
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val ch = value[index++]
        bytes += when {
            ch.code <= 0x7f -> 1
            ch.code <= 0x7ff -> 2
            ch.isHighSurrogate() -> {
                if (index >= value.length || !value[index].isLowSurrogate()) {
                    throw IllegalArgumentException("$label contains an unpaired high surrogate")
                }
                index++
                4
            }
            ch.isLowSurrogate() ->
                throw IllegalArgumentException("$label contains an unpaired low surrogate")
            else -> 3
        }
        if (bytes > CONTEXT_JSON_MAX_UTF8_BYTES) {
            throw IllegalArgumentException("$label exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes")
        }
    }
}

/**
 * Persist the exact Context[] input a node receives before the node process
 * starts. Unlike [encodeContextArg], this always writes a build artifact,
 * including root nodes with an empty Context[] and small contexts that still
 * ride inline on the command line.
 */
internal fun writeInputContextSnapshot(
    items: List<ContextItem>,
    reportRoot: File,
    nodeId: String,
): File {
    val file = inputContextSnapshotFile(reportRoot, nodeId)
    file.parentFile.mkdirs()
    file.writeText(ContextSerde.toJson(items))
    return file
}

/**
 * Load the saved Context[] input for [nodeId] from a previous build/report
 * directory. This is the canonical resume source.
 */
internal fun readInputContextSnapshot(
    reportRoot: File,
    nodeId: String,
): List<ContextItem> {
    val file = inputContextSnapshotFile(reportRoot, nodeId)
    if (!file.isFile) {
        throw IllegalArgumentException(
            "saved input context for node '$nodeId' was not found at ${file.absolutePath}"
        )
    }
    return ContextSerde.fromJson(file)
}

internal fun inputContextSnapshotFile(reportRoot: File, nodeId: String): File =
    File(
        File(reportRoot, "context"),
        "${requireValidNodeId(nodeId, "snapshot nodeId")}.input.json",
    )

/**
 * Writes the Context[] inline or to disk depending on size, and returns
 * the string to pass as --context=<value>.
 */
internal fun encodeContextArg(
    items: List<ContextItem>,
    reportRoot: File,
    stepIndex: Int,
): String {
    val json = ContextSerde.toJson(items)
    if (json.length <= CONTEXT_INLINE_LIMIT) return json
    val dir = File(reportRoot, "context").apply { mkdirs() }
    val file = File(dir, "step-%03d.json".format(stepIndex))
    file.writeText(json)
    return "@" + file.absolutePath
}

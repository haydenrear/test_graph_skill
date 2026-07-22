package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.exec.readBoundedJsonObject
import com.hayden.testgraphsdk.exec.CONTEXT_JSON_MAX_UTF8_BYTES
import com.hayden.testgraphsdk.isValidNodeId
import java.io.File
import java.nio.file.Files

/**
 * Writes one {@code summary.json} + one {@code report.md} for a single
 * test-graph run dir.
 *
 * <p>This used to live inside {@link ValidationReportTask} as the
 * task-action body. Two problems with that location:
 *
 * <ul>
 *   <li>{@code validationReport} is wired as a finalizer on every per-graph
 *       task <em>and</em> on {@code validationRunAll}. Gradle runs a
 *       task at most once per build invocation, so when several graph
 *       tasks share the same finalizer the report task fires once at
 *       a moment that is sensitive to scheduling — in practice, fanning
 *       out across multiple graphs left some run dirs without a report.</li>
 *   <li>It coupled "render markdown for one run dir" to a Gradle task
 *       lifecycle, when the renderer itself is just a pure function
 *       over an envelope dir.</li>
 * </ul>
 *
 * The renderer now lives here as a plain object so {@link
 * com.hayden.testgraphsdk.exec.PlanExecutor} can call it inline at the
 * end of a graph run (guaranteeing every graph emits its report) and
 * {@link ValidationReportTask} can still call it for the
 * "regenerate every existing report" use case.
 */
internal object RunReportWriter {

    data class Outcome(
        val written: Boolean,
        val status: String?,
        val complete: Boolean,
    ) {
        val passed: Boolean
            get() = written && complete && status == "passed"
    }

    private data class ReportIntegrity(
        val expectedNodeIds: List<String>,
        val observedNodeIds: List<String>,
        val missingNodeIds: List<String>,
        val invalidEnvelopeFiles: List<String>,
        val duplicateNodeIds: List<String>,
        val unexpectedNodeIds: List<String>,
        val unknownStatusNodeIds: List<String>,
        val missingTraceNodeIds: List<String>,
        val invalidTraceNodeIds: List<String>,
        val mismatchedTraceNodeIds: List<String>,
        val emptyEvidence: Boolean,
        val envelopeFileCountExceeded: Boolean,
        val aggregateEnvelopeBytesExceeded: Boolean,
        val executionFailure: Throwable?,
    ) {
        val errored: Boolean
            get() = executionFailure != null || missingNodeIds.isNotEmpty() ||
                    invalidEnvelopeFiles.isNotEmpty() || duplicateNodeIds.isNotEmpty() ||
                    unexpectedNodeIds.isNotEmpty() || unknownStatusNodeIds.isNotEmpty() ||
                    missingTraceNodeIds.isNotEmpty() || invalidTraceNodeIds.isNotEmpty() ||
                    mismatchedTraceNodeIds.isNotEmpty() ||
                    emptyEvidence || envelopeFileCountExceeded || aggregateEnvelopeBytesExceeded

        val complete: Boolean
            get() = !errored
    }

    /**
     * Render {@code <runDir>/summary.json} + {@code <runDir>/report.md}
     * from the envelopes already on disk under {@code <runDir>/envelope/}.
     * Idempotent — re-running overwrites both files. No-op when the run
     * dir doesn't have an envelope/ subdir yet.
     *
     * @return the rendered status and integrity decision, or an unwritten
     *         outcome if there was no envelope dir to summarize.
     */
    fun writeRunReport(
        runDir: File,
        expectedNodeIds: List<String> = emptyList(),
        executionFailure: Throwable? = null,
        expectedTraceId: String? = null,
    ): Outcome {
        require(expectedNodeIds.size <= MAX_ENVELOPE_FILES) {
            "expected node count exceeds the absolute report limit of $MAX_ENVELOPE_FILES"
        }
        require(expectedNodeIds.all(::isValidNodeId)) {
            "expected node ids must match [a-z0-9._-]{1,128}"
        }
        require(expectedNodeIds.distinct().size == expectedNodeIds.size) {
            "expected node ids must be unique"
        }
        require(expectedTraceId == null || TRACE_ID.matches(expectedTraceId)) {
            "expected trace id must be 32 lowercase hexadecimal characters"
        }
        val envelopeDir = File(runDir, "envelope")
        if (!envelopeDir.isDirectory && expectedNodeIds.isEmpty() && executionFailure == null) {
            return Outcome(written = false, status = null, complete = false)
        }
        val envelopeFileLimit = envelopeFileLimit(expectedNodeIds.size)
        val envelopeScan = scanEnvelopeFiles(envelopeDir, envelopeFileLimit)
        val envelopeFiles = envelopeScan.files
        var retainedEnvelopeBytes = 0L
        var aggregateEnvelopeBytesExceeded = false
        for (file in envelopeFiles) {
            val fileBytes = file.length()
            if (fileBytes > CONTEXT_JSON_MAX_UTF8_BYTES) continue
            if (retainedEnvelopeBytes > MAX_AGGREGATE_ENVELOPE_BYTES - fileBytes) {
                aggregateEnvelopeBytesExceeded = true
                break
            }
            retainedEnvelopeBytes += fileBytes
        }
        val invalidEnvelopeFiles = mutableListOf<String>()
        val parsed = if (aggregateEnvelopeBytesExceeded) {
            invalidEnvelopeFiles += envelopeFiles.map { it.name }
            emptyList()
        } else envelopeFiles.mapNotNull { file ->
            val bounded = try {
                readBoundedJsonObject(file, "envelope/${file.name}")
            } catch (_: Exception) {
                null
            }
            if (bounded == null) {
                invalidEnvelopeFiles += file.name
                null
            } else {
                val (raw, obj) = bounded
                val nodeId = obj["nodeId"] as? String
                if (
                    nodeId == null || obj["status"] !is String ||
                    !isValidNodeId(nodeId) ||
                    file.name != "$nodeId.json"
                ) {
                    invalidEnvelopeFiles += file.name
                    null
                } else {
                    Triple(file, raw, obj)
                }
            }
        }
        val traceId = expectedTraceId ?: parsed.firstNotNullOfOrNull { (_, _, envelope) ->
            (envelope["traceId"] as? String)?.takeIf(TRACE_ID::matches)
        }
        val traceValidationActive = expectedTraceId != null ||
                parsed.any { (_, _, envelope) -> envelope.containsKey("traceId") }
        val observedNodeIds = parsed.map { (_, _, envelope) -> envelope["nodeId"] as String }
        val observedNodeIdSet = observedNodeIds.toSet()
        val duplicateNodeIds = observedNodeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.sorted()
        val expectedNodeIdSet = expectedNodeIds.toSet()
        val unexpectedNodeIds = if (expectedNodeIds.isEmpty()) {
            emptyList()
        } else {
            (observedNodeIdSet - expectedNodeIdSet).sorted()
        }
        val unknownStatusNodeIds = parsed.mapNotNull { (_, _, envelope) ->
            val status = envelope["status"] as String
            (envelope["nodeId"] as String).takeIf { status !in VALID_NODE_STATUSES }
        }.distinct().sorted()
        val missingTraceNodeIds = if (!traceValidationActive) {
            emptyList()
        } else parsed.mapNotNull { (_, _, envelope) ->
            val nodeId = envelope["nodeId"] as String
            val envelopeTraceId = envelope["traceId"]
            nodeId.takeIf {
                !envelope.containsKey("traceId") ||
                        (envelopeTraceId is String && envelopeTraceId.isBlank())
            }
        }.sorted()
        val invalidTraceNodeIds = if (!traceValidationActive) {
            emptyList()
        } else parsed.mapNotNull { (_, _, envelope) ->
            val nodeId = envelope["nodeId"] as String
            val envelopeTraceId = envelope["traceId"]
            nodeId.takeIf {
                envelope.containsKey("traceId") &&
                        (envelopeTraceId !is String ||
                                (envelopeTraceId.isNotBlank() && !TRACE_ID.matches(envelopeTraceId)))
            }
        }.sorted()
        val mismatchedTraceNodeIds = if (!traceValidationActive) {
            emptyList()
        } else parsed.mapNotNull { (_, _, envelope) ->
            val nodeId = envelope["nodeId"] as String
            val envelopeTraceId = envelope["traceId"] as? String
            nodeId.takeIf {
                !envelopeTraceId.isNullOrBlank() &&
                        TRACE_ID.matches(envelopeTraceId) && envelopeTraceId != traceId
            }
        }.sorted()
        val integrity = ReportIntegrity(
            expectedNodeIds = expectedNodeIds,
            observedNodeIds = observedNodeIds,
            missingNodeIds = expectedNodeIds.filterNot(observedNodeIdSet::contains),
            invalidEnvelopeFiles = invalidEnvelopeFiles.sorted(),
            duplicateNodeIds = duplicateNodeIds,
            unexpectedNodeIds = unexpectedNodeIds,
            unknownStatusNodeIds = unknownStatusNodeIds,
            missingTraceNodeIds = missingTraceNodeIds,
            invalidTraceNodeIds = invalidTraceNodeIds,
            mismatchedTraceNodeIds = mismatchedTraceNodeIds,
            emptyEvidence = envelopeFiles.isEmpty(),
            envelopeFileCountExceeded = envelopeScan.countExceeded,
            aggregateEnvelopeBytesExceeded = aggregateEnvelopeBytesExceeded,
            executionFailure = executionFailure,
        )

        val statusCounts = mutableMapOf<String, Int>()
        for ((_, _, envelope) in parsed) {
            val status = envelope["status"] as String
            statusCounts.merge(status, 1) { a, b -> a + b }
        }
        val overallStatus = when {
            integrity.errored || statusCounts.getOrDefault("errored", 0) > 0 -> "errored"
            statusCounts.getOrDefault("failed", 0) > 0 -> "failed"
            statusCounts.getOrDefault("skipped", 0) > 0 -> "errored"
            else -> "passed"
        }

        // 1. summary.json — machine-readable concatenation.
        val summarySb = StringBuilder()
        summarySb.append('{')
        summarySb.append("\"runId\":").append(jsonString(runDir.name)).append(',')
        summarySb.append("\"status\":").append(jsonString(overallStatus)).append(',')
        if (traceId != null) {
            summarySb.append("\"traceId\":").append(jsonString(traceId)).append(',')
        }
        summarySb.append("\"execution\":{")
        summarySb.append("\"complete\":").append(integrity.complete).append(',')
        summarySb.append("\"expectedNodeIds\":")
        appendJsonStringArray(summarySb, integrity.expectedNodeIds)
        summarySb.append(",\"observedNodeIds\":")
        appendJsonStringArray(summarySb, integrity.observedNodeIds)
        summarySb.append(",\"missingNodeIds\":")
        appendJsonStringArray(summarySb, integrity.missingNodeIds)
        summarySb.append(",\"invalidEnvelopeFiles\":")
        appendJsonStringArray(summarySb, integrity.invalidEnvelopeFiles)
        summarySb.append(",\"duplicateNodeIds\":")
        appendJsonStringArray(summarySb, integrity.duplicateNodeIds)
        summarySb.append(",\"unexpectedNodeIds\":")
        appendJsonStringArray(summarySb, integrity.unexpectedNodeIds)
        summarySb.append(",\"unknownStatusNodeIds\":")
        appendJsonStringArray(summarySb, integrity.unknownStatusNodeIds)
        summarySb.append(",\"missingTraceNodeIds\":")
        appendJsonStringArray(summarySb, integrity.missingTraceNodeIds)
        summarySb.append(",\"invalidTraceNodeIds\":")
        appendJsonStringArray(summarySb, integrity.invalidTraceNodeIds)
        summarySb.append(",\"mismatchedTraceNodeIds\":")
        appendJsonStringArray(summarySb, integrity.mismatchedTraceNodeIds)
        summarySb.append(",\"envelopeFileCountExceeded\":")
            .append(integrity.envelopeFileCountExceeded)
        summarySb.append(",\"aggregateEnvelopeBytesExceeded\":")
            .append(integrity.aggregateEnvelopeBytesExceeded)
        integrity.executionFailure?.let { failure ->
            summarySb.append(",\"failure\":{")
            summarySb.append("\"type\":").append(jsonString(failure.javaClass.name)).append(',')
            summarySb.append("\"message\":").append(
                jsonString((failure.message ?: "").take(MAX_FAILURE_MESSAGE_CHARS))
            )
            summarySb.append('}')
        }
        summarySb.append("},")
        summarySb.append("\"nodes\":[")
        parsed.forEachIndexed { i, (_, raw, _) ->
            if (i > 0) summarySb.append(',')
            summarySb.append(raw.trim())
        }
        summarySb.append("]}")
        File(runDir, "summary.json").writeText(summarySb.toString())

        // 2. report.md — human-friendly per-run report.
        val report = renderReport(
            runId = runDir.name,
            traceId = traceId,
            envelopes = parsed.map { (file, _, envelope) -> file to envelope },
            integrity = integrity,
            overallStatus = overallStatus,
            statusCounts = statusCounts,
        ).trimEnd() + "\n"
        File(runDir, "report.md").writeText(report)
        return Outcome(
            written = true,
            status = overallStatus,
            complete = integrity.complete,
        )
    }

    private fun renderReport(
        runId: String,
        traceId: String?,
        envelopes: List<Pair<File, Map<*, *>>>,
        integrity: ReportIntegrity,
        overallStatus: String,
        statusCounts: Map<String, Int>,
    ): String {
        val sb = StringBuilder()

        // Roll-up counts so the report header tells the story at a glance.
        val total = envelopes.size
        val passed = statusCounts.getOrDefault("passed", 0)
        val failed = statusCounts.getOrDefault("failed", 0)
        val errored = statusCounts.getOrDefault("errored", 0)
        val skipped = statusCounts.getOrDefault("skipped", 0)

        sb.append("# Validation report — ").append(runId).append("\n\n")
        if (traceId != null) {
            sb.append("**Trace ID**: `").append(traceId).append("`\n\n")
        }
        sb.append("**Overall**: ").append(overallStatus.uppercase()).append("\n\n")
        if (integrity.expectedNodeIds.isNotEmpty()) {
            val expectedObserved = integrity.expectedNodeIds.count(integrity.observedNodeIds.toSet()::contains)
            sb.append("**Plan evidence**: ").append(expectedObserved)
                .append('/').append(integrity.expectedNodeIds.size)
                .append(" expected node envelopes observed\n\n")
        }
        if (integrity.missingNodeIds.isNotEmpty()) {
            sb.append("**Missing node envelopes**: ")
                .append(integrity.missingNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        integrity.executionFailure?.let { failure ->
            sb.append("**Execution error**: `").append(failure.javaClass.name).append('`')
            failure.message?.take(MAX_FAILURE_MESSAGE_CHARS)?.takeIf { it.isNotBlank() }?.let {
                sb.append(" — ").append(it.replace("\n", " ").replace("\r", " "))
            }
            sb.append("\n\n")
        }
        if (integrity.invalidEnvelopeFiles.isNotEmpty()) {
            sb.append("**Invalid envelope files**: ")
                .append(integrity.invalidEnvelopeFiles.joinToString(", ") { "`envelope/$it`" })
                .append("\n\n")
        }
        if (integrity.duplicateNodeIds.isNotEmpty()) {
            sb.append("**Duplicate node envelopes**: ")
                .append(integrity.duplicateNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.unexpectedNodeIds.isNotEmpty()) {
            sb.append("**Unexpected node envelopes**: ")
                .append(integrity.unexpectedNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.unknownStatusNodeIds.isNotEmpty()) {
            sb.append("**Unknown node statuses**: ")
                .append(integrity.unknownStatusNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.missingTraceNodeIds.isNotEmpty()) {
            sb.append("**Missing node trace IDs**: ")
                .append(integrity.missingTraceNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.invalidTraceNodeIds.isNotEmpty()) {
            sb.append("**Invalid node trace IDs**: ")
                .append(integrity.invalidTraceNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.mismatchedTraceNodeIds.isNotEmpty()) {
            sb.append("**Mismatched node trace IDs**: ")
                .append(integrity.mismatchedTraceNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.emptyEvidence) {
            sb.append("**Execution evidence**: no node envelopes were produced\n\n")
        }
        if (integrity.envelopeFileCountExceeded) {
            sb.append("**Envelope file count**: exceeded the bounded scan limit; ")
                .append("only bounded partial evidence was retained\n\n")
        }
        if (integrity.aggregateEnvelopeBytesExceeded) {
            sb.append("**Aggregate envelope bytes**: exceeded ")
                .append(MAX_AGGREGATE_ENVELOPE_BYTES)
                .append(" bytes; envelope parsing was skipped\n\n")
        }
        sb.append("**Nodes**: ").append(total)
        sb.append(" (passed=").append(passed)
        sb.append(", failed=").append(failed)
        sb.append(", errored=").append(errored)
        if (skipped > 0) sb.append(", skipped=").append(skipped)
        sb.append(")\n\n")

        // Plan summary table — quickest scan path: status + duration per node.
        sb.append("| Node | Status | Duration | Input context | Captured stdout |\n")
        sb.append("|---|---|---|---|---|\n")
        for ((_, env) in envelopes) {
            val nodeId = (env["nodeId"] as? String) ?: "?"
            val status = (env["status"] as? String) ?: "?"
            val durationMs = durationFromExecutor(env)
            val durationStr = if (durationMs >= 0) "${durationMs}ms" else "—"
            val inputContextPath = env["inputContextFile"] as? String
            val inputContextCell = if (inputContextPath != null) "[$inputContextPath]($inputContextPath)" else "—"
            val stdoutPath = env["capturedStdoutLog"] as? String
            val stdoutCell = if (stdoutPath != null) "[$stdoutPath]($stdoutPath)" else "—"
            sb.append("| `").append(nodeId).append("` | ").append(badge(status))
              .append(" | ").append(durationStr)
              .append(" | ").append(inputContextCell)
              .append(" | ").append(stdoutCell).append(" |\n")
        }
        sb.append('\n')

        // One section per node, in plan order.
        for ((_, env) in envelopes) {
            renderNode(sb, env)
        }
        return sb.toString()
    }

    private fun renderNode(sb: StringBuilder, env: Map<*, *>) {
        val nodeId = (env["nodeId"] as? String) ?: "?"
        val status = (env["status"] as? String) ?: "?"
        sb.append("## `").append(nodeId).append("` — ").append(badge(status)).append("\n\n")

        val failureMessage = env["failureMessage"] as? String
        if (failureMessage != null) {
            sb.append("**Failure**: ").append(failureMessage).append("\n\n")
        }
        val errorStack = env["errorStack"] as? String
        if (errorStack != null) {
            sb.append("<details><summary>Error stack</summary>\n\n```\n")
              .append(errorStack.trim()).append("\n```\n</details>\n\n")
        }

        // Timing: prefer executor-measured (covers the full spawn) when
        // present, fall back to body-internal (legacy / SDK-stamped).
        val timingLines = mutableListOf<String>()
        (env["executorStartedAt"] as? String)?.let {
            timingLines += "executor start: `$it`"
        }
        (env["executorEndedAt"] as? String)?.let {
            timingLines += "executor end: `$it`"
        }
        (env["spawnExitCode"] as? Number)?.let {
            timingLines += "spawn exit code: $it"
        }
        if (timingLines.isNotEmpty()) {
            sb.append(timingLines.joinToString(separator = "\n\n")).append("\n\n")
        }

        val inputContextPath = env["inputContextFile"] as? String
        if (inputContextPath != null) {
            sb.append("**Input context**: [")
              .append(inputContextPath).append("](").append(inputContextPath).append(")\n\n")
        }

        renderRerunGuidance(sb, env["rerunGuidance"])
        renderAssertions(sb, env["assertions"])
        renderMetrics(sb, env["metrics"])
        renderProcesses(sb, env["processes"])
        renderArtifacts(sb, env["artifacts"])
        renderPublished(sb, env["published"])
        renderProvisioningState(sb, env["provisioningState"])
        renderInlineLogs(sb, env["logs"])

        // Captured node-process stdout pointer.
        val stdoutPath = env["capturedStdoutLog"] as? String
        if (stdoutPath != null) {
            sb.append("**Node-process stdout**: [")
              .append(stdoutPath).append("](").append(stdoutPath).append(")\n\n")
        }
        sb.append("---\n\n")
    }

    private fun renderRerunGuidance(sb: StringBuilder, raw: Any?) {
        val map = (raw as? Map<*, *>) ?: return
        val resumeGraph = map["resumeGraphCommand"] as? String
        val runOnly = map["runOnlyCommand"] as? String
        if (resumeGraph == null && runOnly == null) return
        sb.append("### Rerun guidance\n\n")
        val inputContext = map["inputContextFile"] as? String
        if (inputContext != null) {
            sb.append("Saved input context: [`")
              .append(inputContext).append("`](").append(inputContext).append(")\n\n")
        }
        if (resumeGraph != null) {
            sb.append("Resume graph:\n\n```bash\n")
              .append(resumeGraph).append("\n```\n\n")
        }
        if (runOnly != null) {
            sb.append("Run only this node:\n\n```bash\n")
              .append(runOnly).append("\n```\n\n")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderAssertions(sb: StringBuilder, raw: Any?) {
        val list = (raw as? List<*>) ?: return
        if (list.isEmpty()) return
        sb.append("### Assertions\n\n")
        sb.append("| Name | Status |\n|---|---|\n")
        for (item in list) {
            val a = item as? Map<*, *> ?: continue
            sb.append("| ").append(a["name"]).append(" | ").append(badge(a["status"] as? String)).append(" |\n")
        }
        sb.append('\n')
    }

    private fun renderMetrics(sb: StringBuilder, raw: Any?) {
        val map = (raw as? Map<*, *>) ?: return
        if (map.isEmpty()) return
        sb.append("### Metrics\n\n")
        for ((k, v) in map) {
            sb.append("- `").append(k).append("`: ").append(v).append("\n")
        }
        sb.append('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderProcesses(sb: StringBuilder, raw: Any?) {
        val list = (raw as? List<*>) ?: return
        if (list.isEmpty()) return
        sb.append("### Subprocesses\n\n")
        sb.append("| Label | Exit | Duration | PID | Log | Error |\n")
        sb.append("|---|---|---|---|---|---|\n")
        for (item in list) {
            val p = item as? Map<*, *> ?: continue
            val label = p["label"] ?: "?"
            val exit = p["exitCode"] ?: "—"
            val duration = processDurationMs(p)
            val durationStr = if (duration >= 0) "${duration}ms" else "—"
            val pid = p["pid"] ?: "—"
            val logPath = p["log"] as? String
            val logCell = if (logPath != null) "[`$logPath`]($logPath)" else "—"
            val error = (p["error"] as? String)?.let { it.replace("|", "\\|") } ?: ""
            sb.append("| ").append(label)
              .append(" | ").append(exit)
              .append(" | ").append(durationStr)
              .append(" | ").append(pid)
              .append(" | ").append(logCell)
              .append(" | ").append(error).append(" |\n")
        }
        sb.append('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderArtifacts(sb: StringBuilder, raw: Any?) {
        val list = (raw as? List<*>) ?: return
        if (list.isEmpty()) return
        sb.append("### Artifacts\n\n")
        for (item in list) {
            val a = item as? Map<*, *> ?: continue
            val type = a["type"] ?: "?"
            val path = a["path"] as? String ?: continue
            sb.append("- `").append(type).append("` — [`").append(path).append("`](").append(path).append(")\n")
        }
        sb.append('\n')
    }

    private fun renderPublished(sb: StringBuilder, raw: Any?) {
        val map = (raw as? Map<*, *>) ?: return
        if (map.isEmpty()) return
        sb.append("### Published context\n\n")
        for ((k, v) in map) {
            sb.append("- `").append(k).append("`: `").append(v).append("`\n")
        }
        sb.append('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderProvisioningState(sb: StringBuilder, raw: Any?) {
        val map = (raw as? Map<*, *>) ?: return
        if (map.isEmpty()) return
        sb.append("### Provisioning state\n\n")
        listOf("environmentId", "branch", "target", "backend").forEach { key ->
            (map[key] as? String)?.let { sb.append("- `").append(key).append("`: `").append(it).append("`\n") }
        }
        (map["actions"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { actions ->
            sb.append("- `actions`: `").append(actions.joinToString(",")).append("`\n")
        }
        listOf("provisionedMarker", "deployedMarker", "resetMarker", "destroyRequestMarker", "destroyedMarker").forEach { key ->
            (map[key] as? String)?.let { path ->
                sb.append("- `").append(key).append("`: [`").append(path).append("`](").append(path).append(")\n")
            }
        }
        sb.append('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderInlineLogs(sb: StringBuilder, raw: Any?) {
        val list = (raw as? List<*>) ?: return
        if (list.isEmpty()) return
        sb.append("### Inline logs\n\n```\n")
        for (line in list) {
            sb.append(line).append('\n')
        }
        sb.append("```\n\n")
    }

    private fun durationFromExecutor(env: Map<*, *>): Long {
        val start = env["executorStartedAt"] as? String
        val end = env["executorEndedAt"] as? String
        return diffMs(start, end)
    }

    private fun processDurationMs(p: Map<*, *>): Long {
        val start = p["startedAt"] as? String
        val end = p["endedAt"] as? String
        return diffMs(start, end)
    }

    private fun diffMs(startIso: String?, endIso: String?): Long {
        if (startIso == null || endIso == null) return -1
        return try {
            java.time.Instant.parse(endIso).toEpochMilli() -
                    java.time.Instant.parse(startIso).toEpochMilli()
        } catch (e: Exception) {
            -1
        }
    }

    private fun badge(status: String?): String = when (status) {
        "passed" -> "**PASS**"
        "failed" -> "**FAIL**"
        "errored" -> "**ERROR**"
        "skipped" -> "_skipped_"
        else -> status ?: "?"
    }

    private fun appendJsonStringArray(sb: StringBuilder, values: List<String>) {
        sb.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) sb.append(',')
            sb.append(jsonString(value))
        }
        sb.append(']')
    }

    internal data class EnvelopeFileScan(
        val files: List<File>,
        val countExceeded: Boolean,
    )

    internal fun envelopeFileLimit(expectedNodeCount: Int): Int {
        require(expectedNodeCount >= 0) { "expected node count must be non-negative" }
        return if (expectedNodeCount == 0) {
            MAX_ENVELOPE_FILES
        } else {
            expectedNodeCount.coerceAtMost(MAX_ENVELOPE_FILES)
        }
    }

    internal fun scanEnvelopeFiles(envelopeDir: File, maxFiles: Int): EnvelopeFileScan {
        require(maxFiles >= 0) { "envelope file limit must be non-negative" }
        if (!envelopeDir.isDirectory) return EnvelopeFileScan(emptyList(), false)

        val retained = ArrayList<File>(maxFiles.coerceAtMost(1_024))
        var countExceeded = false
        Files.newDirectoryStream(envelopeDir.toPath()).use { entries ->
            for (entry in entries) {
                if (!entry.fileName.toString().endsWith(".json")) continue
                if (retained.size == maxFiles) {
                    countExceeded = true
                    break
                }
                retained += entry.toFile()
            }
        }
        retained.sortBy { it.name }
        return EnvelopeFileScan(retained, countExceeded)
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    private const val MAX_FAILURE_MESSAGE_CHARS = 4_096
    internal const val MAX_AGGREGATE_ENVELOPE_BYTES = 64L * 1024 * 1024
    internal const val MAX_ENVELOPE_FILES = 10_000
    private val VALID_NODE_STATUSES = setOf("passed", "failed", "errored", "skipped")
    private val TRACE_ID = Regex("^[0-9a-f]{32}$")
}

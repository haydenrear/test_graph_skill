package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.exec.CONTEXT_JSON_MAX_DEPTH
import com.hayden.testgraphsdk.exec.CONTEXT_JSON_MAX_UTF8_BYTES
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunReportWriterTraceTest {

    @Test
    fun exposesTheEnvelopeTraceIdInSummaryAndMarkdown() {
        val runDir = Files.createTempDirectory("test-graph-report").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        val traceId = "0123456789abcdef0123456789abcdef"
        envelopeDir.resolve("probe.json").writeText(
            """
            {
              "nodeId":"probe",
              "status":"passed",
              "traceId":"$traceId",
              "metrics":{},
              "published":{}
            }
            """.trimIndent()
        )

        RunReportWriter.writeRunReport(runDir)

        assertContains(runDir.resolve("summary.json").readText(), "\"traceId\":\"$traceId\"")
        assertContains(runDir.resolve("report.md").readText(), "**Trace ID**: `$traceId`")
    }

    @Test
    fun abortedFiveNodeRunIsErroredAndRetainsThreeCompletedNodeEnvelopes() {
        val runDir = Files.createTempDirectory("test-graph-aborted-report").toFile()
        writePassingEnvelopes(runDir, listOf("one", "two", "three"))

        RunReportWriter.writeRunReport(
            runDir = runDir,
            expectedNodeIds = listOf("one", "two", "three", "four", "five"),
            executionFailure = StackOverflowError("context extraction overflowed"),
        )

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        val execution = MiniJson.obj(summary["execution"])
        assertFalse(execution["complete"] as Boolean)
        assertEquals(listOf("four", "five"), MiniJson.stringList(execution["missingNodeIds"]))
        assertEquals(
            "java.lang.StackOverflowError",
            MiniJson.obj(execution["failure"])["type"],
        )
        val retainedNodeIds = MiniJson.list(summary["nodes"])
            .map { MiniJson.obj(it)["nodeId"] as String }
            .toSet()
        assertEquals(setOf("one", "two", "three"), retainedNodeIds)

        val markdown = runDir.resolve("report.md").readText()
        assertContains(markdown, "**Overall**: ERRORED")
        assertContains(markdown, "**Plan evidence**: 3/5 expected node envelopes observed")
        assertContains(markdown, "**Missing node envelopes**: `four`, `five`")
        assertContains(markdown, "## `one` — **PASS**")
    }

    @Test
    fun incompletePlanWithoutAThrowableIsStillErrored() {
        val runDir = Files.createTempDirectory("test-graph-incomplete-report").toFile()
        writePassingEnvelopes(runDir, listOf("one", "two", "three"))

        val reportOutcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            expectedNodeIds = listOf("one", "two", "three", "four", "five"),
        )

        assertFalse(reportOutcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        assertContains(runDir.resolve("report.md").readText(), "**Overall**: ERRORED")
    }

    @Test
    fun envelopeFilenameMustMatchItsEmbeddedNodeIdentity() {
        val runDir = Files.createTempDirectory("test-graph-envelope-identity").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("expected.json").writeText(
            """{"nodeId":"impersonator","status":"passed","metrics":{},"published":{}}"""
        )

        val reportOutcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            expectedNodeIds = listOf("expected"),
        )

        assertFalse(reportOutcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(listOf("expected.json"), MiniJson.stringList(execution["invalidEnvelopeFiles"]))
        assertEquals(listOf("expected"), MiniJson.stringList(execution["missingNodeIds"]))
        assertTrue(MiniJson.list(summary["nodes"]).isEmpty())
    }

    @Test
    fun unsafeEmbeddedNodeIdentityIsInvalidEvidence() {
        val runDir = Files.createTempDirectory("test-graph-unsafe-envelope-id").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("unsafe.json").writeText(
            """{"nodeId":"a:b","status":"passed","metrics":{},"published":{}}"""
        )

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            expectedNodeIds = listOf("unsafe"),
        )

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals(listOf("unsafe.json"), MiniJson.stringList(execution["invalidEnvelopeFiles"]))
        assertEquals(listOf("unsafe"), MiniJson.stringList(execution["missingNodeIds"]))
    }

    @Test
    fun activeRunRejectsMissingAndMismatchedEnvelopeTraceIds() {
        val runDir = Files.createTempDirectory("test-graph-envelope-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        val expectedTraceId = "0123456789abcdef0123456789abcdef"
        envelopeDir.resolve("correct.json").writeText(
            """{"nodeId":"correct","status":"passed","traceId":"$expectedTraceId"}"""
        )
        envelopeDir.resolve("missing.json").writeText(
            """{"nodeId":"missing","status":"passed"}"""
        )
        envelopeDir.resolve("mismatch.json").writeText(
            """{"nodeId":"mismatch","status":"passed","traceId":"fedcba9876543210fedcba9876543210"}"""
        )
        envelopeDir.resolve("numeric.json").writeText(
            """{"nodeId":"numeric","status":"passed","traceId":7}"""
        )

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            expectedNodeIds = listOf("correct", "missing", "mismatch", "numeric"),
            expectedTraceId = expectedTraceId,
        )

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(expectedTraceId, summary["traceId"])
        assertEquals(listOf("missing"), MiniJson.stringList(execution["missingTraceNodeIds"]))
        assertEquals(listOf("numeric"), MiniJson.stringList(execution["invalidTraceNodeIds"]))
        assertEquals(listOf("mismatch"), MiniJson.stringList(execution["mismatchedTraceNodeIds"]))
        val markdown = runDir.resolve("report.md").readText()
        assertContains(markdown, "**Missing node trace IDs**: `missing`")
        assertContains(markdown, "**Invalid node trace IDs**: `numeric`")
        assertContains(markdown, "**Mismatched node trace IDs**: `mismatch`")
    }

    @Test
    fun manualReportRejectsAllSameInvalidTraceIdsWithoutPublishingOne() {
        val runDir = Files.createTempDirectory("test-graph-manual-invalid-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        for (nodeId in listOf("one", "two")) {
            envelopeDir.resolve("$nodeId.json").writeText(
                """{"nodeId":"$nodeId","status":"passed","traceId":"foo"}"""
            )
        }

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertFalse(summary.containsKey("traceId"))
        assertEquals(listOf("one", "two"), MiniJson.stringList(execution["invalidTraceNodeIds"]))
    }

    @Test
    fun manualReportTreatsPresentBlankTraceIdsAsMissingNotLegacy() {
        val runDir = Files.createTempDirectory("test-graph-manual-blank-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("blank.json").writeText(
            """{"nodeId":"blank","status":"passed","traceId":""}"""
        )
        envelopeDir.resolve("whitespace.json").writeText(
            """{"nodeId":"whitespace","status":"passed","traceId":"  "}"""
        )

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertFalse(summary.containsKey("traceId"))
        assertEquals(
            listOf("blank", "whitespace"),
            MiniJson.stringList(execution["missingTraceNodeIds"]),
        )
    }

    @Test
    fun manualReportDerivesOnlyTheValidTraceWhenInvalidAndValidAreMixed() {
        val runDir = Files.createTempDirectory("test-graph-manual-valid-invalid-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        val validTraceId = "0123456789abcdef0123456789abcdef"
        envelopeDir.resolve("invalid.json").writeText(
            """{"nodeId":"invalid","status":"passed","traceId":"foo"}"""
        )
        envelopeDir.resolve("valid.json").writeText(
            """{"nodeId":"valid","status":"passed","traceId":"$validTraceId"}"""
        )

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals(validTraceId, summary["traceId"])
        assertEquals(listOf("invalid"), MiniJson.stringList(execution["invalidTraceNodeIds"]))
        assertTrue(MiniJson.stringList(execution["mismatchedTraceNodeIds"]).isEmpty())
    }

    @Test
    fun manualReportRejectsMixedTracesOnceAnyEnvelopeIsTraced() {
        val runDir = Files.createTempDirectory("test-graph-manual-mixed-traces").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        val firstTraceId = "0123456789abcdef0123456789abcdef"
        envelopeDir.resolve("first.json").writeText(
            """{"nodeId":"first","status":"passed","traceId":"$firstTraceId"}"""
        )
        envelopeDir.resolve("legacy.json").writeText(
            """{"nodeId":"legacy","status":"passed"}"""
        )
        envelopeDir.resolve("other.json").writeText(
            """{"nodeId":"other","status":"passed","traceId":"fedcba9876543210fedcba9876543210"}"""
        )

        val outcome = RunReportWriter.writeRunReport(runDir)

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals(firstTraceId, summary["traceId"])
        assertEquals(listOf("legacy"), MiniJson.stringList(execution["missingTraceNodeIds"]))
        assertEquals(listOf("other"), MiniJson.stringList(execution["mismatchedTraceNodeIds"]))
    }

    @Test
    fun terminalDecisionUsesReportIntegrityForTaskAndTelemetry() {
        val passedDir = Files.createTempDirectory("test-graph-passed-outcome").toFile()
        writePassingEnvelopes(passedDir, listOf("only"))
        val passedReport = RunReportWriter.writeRunReport(
            runDir = passedDir,
            expectedNodeIds = listOf("only"),
        )

        val passed = resolveRunTerminalOutcome(null, passedReport, null)
        assertNull(passed.failure)
        assertEquals("passed", passed.observabilityStatus)

        val incompleteDir = Files.createTempDirectory("test-graph-failed-outcome").toFile()
        incompleteDir.resolve("envelope").mkdirs()
        val incompleteReport = RunReportWriter.writeRunReport(
            runDir = incompleteDir,
            expectedNodeIds = listOf("missing"),
        )
        val failed = resolveRunTerminalOutcome(null, incompleteReport, null)
        assertTrue(failed.failure is IllegalStateException)
        assertEquals("failed", failed.observabilityStatus)

        requireExecutablePlanSize(RunReportWriter.MAX_ENVELOPE_FILES)
        assertFailsWith<IllegalArgumentException> { requireExecutablePlanSize(0) }
        assertFailsWith<IllegalArgumentException> {
            requireExecutablePlanSize(RunReportWriter.MAX_ENVELOPE_FILES + 1)
        }
    }

    @Test
    fun terminalDecisionPreservesExecutionFailureAndAttachesReportWriteFailure() {
        val executionFailure = StackOverflowError("original execution failure")
        val reportWriteFailure = IllegalStateException("report write failure")

        val terminal = resolveRunTerminalOutcome(
            executionFailure = executionFailure,
            reportOutcome = null,
            reportWriteFailure = reportWriteFailure,
        )

        assertSame(executionFailure, terminal.failure)
        assertEquals(listOf(reportWriteFailure), executionFailure.suppressed.toList())
        assertEquals("failed", terminal.observabilityStatus)

        val reportOnly = resolveRunTerminalOutcome(
            executionFailure = null,
            reportOutcome = null,
            reportWriteFailure = reportWriteFailure,
        )
        assertSame(reportWriteFailure, reportOnly.failure)
        assertEquals("failed", reportOnly.observabilityStatus)
    }

    @Test
    fun unexpectedNodesAndUnknownStatusesAreIntegrityErrors() {
        val runDir = Files.createTempDirectory("test-graph-unexpected-report").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("expected.json").writeText(
            """{"nodeId":"expected","status":"passed","metrics":{},"published":{}}"""
        )
        envelopeDir.resolve("rogue.json").writeText(
            """{"nodeId":"rogue","status":"mystery","metrics":{},"published":{}}"""
        )

        RunReportWriter.writeRunReport(
            runDir,
            expectedNodeIds = listOf("expected", "planned-but-missing"),
        )

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(listOf("rogue"), MiniJson.stringList(execution["unexpectedNodeIds"]))
        assertEquals(listOf("rogue"), MiniJson.stringList(execution["unknownStatusNodeIds"]))
    }

    @Test
    fun overDeepAndOversizedEnvelopeFilesFailClosedAsInvalidEvidence() {
        val runDir = Files.createTempDirectory("test-graph-bounded-report").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("deep.json").writeText(buildString {
            append("{\"nodeId\":\"deep\",\"status\":\"passed\",\"nested\":")
            repeat(CONTEXT_JSON_MAX_DEPTH + 1) { append('[') }
            append('0')
            repeat(CONTEXT_JSON_MAX_DEPTH + 1) { append(']') }
            append('}')
        })
        RandomAccessFile(envelopeDir.resolve("huge.json"), "rw").use {
            it.setLength(CONTEXT_JSON_MAX_UTF8_BYTES.toLong() + 1)
        }

        RunReportWriter.writeRunReport(runDir, expectedNodeIds = listOf("deep", "huge"))

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(
            listOf("deep.json", "huge.json"),
            MiniJson.stringList(execution["invalidEnvelopeFiles"]),
        )
        assertContains(runDir.resolve("report.md").readText(), "**Invalid envelope files**")
    }

    @Test
    fun emptyEnvelopeDirectoryNeverRendersPassed() {
        val runDir = Files.createTempDirectory("test-graph-empty-report").toFile()
        runDir.resolve("envelope").mkdirs()

        RunReportWriter.writeRunReport(runDir)

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        assertContains(runDir.resolve("report.md").readText(), "no node envelopes were produced")
    }

    @Test
    fun aggregateEnvelopeBytesAreBoundedBeforeAnyFileIsRead() {
        val runDir = Files.createTempDirectory("test-graph-aggregate-report").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        repeat(5) { index ->
            RandomAccessFile(envelopeDir.resolve("sparse-$index.json"), "rw").use {
                it.setLength(16L * 1024 * 1024)
            }
        }

        RunReportWriter.writeRunReport(
            runDir,
            expectedNodeIds = List(5) { index -> "sparse-$index" },
        )

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(true, execution["aggregateEnvelopeBytesExceeded"])
        assertEquals(0, MiniJson.list(summary["nodes"]).size)
        assertContains(runDir.resolve("report.md").readText(), "envelope parsing was skipped")
    }

    @Test
    fun expectedPlanBoundsEnvelopeDirectoryEnumerationAtTheNextJsonFile() {
        val runDir = Files.createTempDirectory("test-graph-envelope-count").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("expected.json").writeText(
            """{"nodeId":"expected","status":"passed","metrics":{},"published":{}}"""
        )
        envelopeDir.resolve("excess.json").writeText(
            """{"nodeId":"excess","status":"passed","metrics":{},"published":{}}"""
        )

        val outcome = RunReportWriter.writeRunReport(
            runDir = runDir,
            expectedNodeIds = listOf("expected"),
        )

        assertFalse(outcome.passed)
        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        val execution = MiniJson.obj(summary["execution"])
        assertEquals("errored", summary["status"])
        assertEquals(true, execution["envelopeFileCountExceeded"])
        assertTrue(MiniJson.list(summary["nodes"]).size <= 1)
        assertContains(runDir.resolve("report.md").readText(), "**Envelope file count**")
    }

    @Test
    fun unknownPlanDirectoryScanHasAnExplicitBoundedSeam() {
        val envelopeDir = Files.createTempDirectory("test-graph-manual-envelope-count").toFile()
        repeat(3) { index ->
            envelopeDir.resolve("node-$index.json").writeText("{}")
        }

        val scan = RunReportWriter.scanEnvelopeFiles(envelopeDir, maxFiles = 2)

        assertEquals(2, scan.files.size)
        assertTrue(scan.countExceeded)
        assertEquals(
            RunReportWriter.MAX_ENVELOPE_FILES,
            RunReportWriter.envelopeFileLimit(RunReportWriter.MAX_ENVELOPE_FILES + 1),
        )
    }

    @Test
    fun oversizedExpectedPlanIsRejectedBeforeAnyElementIsTraversed() {
        val oversizedPlan = object : AbstractList<String>() {
            override val size: Int = RunReportWriter.MAX_ENVELOPE_FILES + 1
            override fun get(index: Int): String =
                error("oversized expected plan must not be traversed")
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            RunReportWriter.writeRunReport(
                runDir = Files.createTempDirectory("test-graph-oversized-plan").toFile(),
                expectedNodeIds = oversizedPlan,
            )
        }

        assertContains(failure.message.orEmpty(), "absolute report limit")
    }

    @Test
    fun regeneratedReportWithSkippedEvidenceNeverRendersPassed() {
        val runDir = Files.createTempDirectory("test-graph-skipped-report").toFile()
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        envelopeDir.resolve("skipped.json").writeText(
            """{"nodeId":"skipped","status":"skipped","metrics":{},"published":{}}"""
        )

        RunReportWriter.writeRunReport(runDir)

        val summary = MiniJson.obj(MiniJson.parse(runDir.resolve("summary.json").readText()))
        assertEquals("errored", summary["status"])
        assertContains(runDir.resolve("report.md").readText(), "**Overall**: ERRORED")
    }

    private fun writePassingEnvelopes(runDir: java.io.File, nodeIds: List<String>) {
        val envelopeDir = runDir.resolve("envelope").apply { mkdirs() }
        for (nodeId in nodeIds) {
            envelopeDir.resolve("$nodeId.json").writeText(
                """{"nodeId":"$nodeId","status":"passed","metrics":{},"published":{}}"""
            )
        }
    }
}

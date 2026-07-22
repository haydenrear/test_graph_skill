package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.NodeKind
import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlanExecutorResultIntegrityTest {

    @Test
    fun rejectsAChildResultForAnotherNodeBeforePublishedContextIsAccepted() {
        val impersonator = "x".repeat(8_192)
        val (envelope, failure) = runSingleNode { invocation ->
            invocation.resultOut.parentFile.mkdirs()
            invocation.resultOut.writeText(
                """{"nodeId":"$impersonator","status":"passed","published":{"poison":"must-not-flow"}}"""
            )
        }

        val parsed = MiniJson.obj(MiniJson.parse(envelope.readText()))
        assertEquals("planned.node", parsed["nodeId"])
        assertEquals("errored", parsed["status"])
        assertEquals(emptyMap<String, String>(), MiniJson.stringMap(parsed["published"]))
        val failureMessage = parsed["failureMessage"] as String
        assertContains(failureMessage, "expected planned.node")
        assertTrue(failureMessage.length < 512, "result node identity preview must stay bounded")
        assertContains(failure.message ?: "", "node planned.node errored")
    }

    @Test
    fun oversizedSparseChildResultIsRejectedWithOnlyABoundedPreview() {
        val (envelope, _) = runSingleNode { invocation ->
            invocation.resultOut.parentFile.mkdirs()
            RandomAccessFile(invocation.resultOut, "rw").use {
                it.setLength(CONTEXT_JSON_MAX_UTF8_BYTES.toLong() + 1)
            }
        }

        val parsed = MiniJson.obj(MiniJson.parse(envelope.readText()))
        assertEquals("errored", parsed["status"])
        assertContains(parsed["failureMessage"] as String, "exceeds $CONTEXT_JSON_MAX_UTF8_BYTES")
        val preview = parsed["malformedResultOutPreview"] as String
        assertTrue(preview.length <= 4_096)
        assertTrue(envelope.length() < 64 * 1024, "canonical envelope must stay bounded")
    }

    private fun runSingleNode(
        writeResult: (NodeInvocation) -> Unit,
    ): Pair<File, Throwable> {
        disableExportForUnitTest()
        val projectRoot = Files.createTempDirectory("test-graph-result-integrity").toFile()
        val project = ProjectBuilder.builder().withProjectDir(projectRoot).build()
        val reportRoot = projectRoot.resolve("report").apply { mkdirs() }
        val reportDirectory = project.layout.dir(project.provider { reportRoot }).get()
        val observability = GraphObservability.open(reportRoot, "resultIntegrity")
        val spec = ValidationNodeSpec(
            id = "planned.node",
            kind = NodeKind.ASSERTION,
            runtime = ValidationRuntime.Uv("node.py"),
        )
        val executor = object : ValidationExecutor {
            override val runtimeName: String = "uv"

            override fun execute(invocation: NodeInvocation): ExecutionOutcome {
                writeResult(invocation)
                return ExecutionOutcome.Completed(0)
            }
        }

        val failure = try {
            assertFailsWith<RuntimeException> {
                PlanExecutor(
                    registry = ExecutorRegistry(mapOf("uv" to executor)),
                    projectDir = project.layout.projectDirectory,
                    reportDir = reportDirectory,
                    runId = "result-integrity",
                    logger = project.logger,
                    graphName = "resultIntegrity",
                    observability = observability,
                ).run(listOf(spec))
            }
        } finally {
            observability.finish("failed", timeoutMillis = 10)
        }
        return reportRoot.resolve("envelope/planned.node.json") to failure
    }

    private fun disableExportForUnitTest() {
        System.setProperty("otel.traces.exporter", "none")
        System.setProperty("otel.metrics.exporter", "none")
        System.setProperty("otel.logs.exporter", "none")
    }
}

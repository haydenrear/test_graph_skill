package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphObservabilityTest {

    @Test
    fun persistsOneW3cCarrierAndReusesItForReplay() {
        disableExportForUnitTest()
        val reportDir = Files.createTempDirectory("test-graph-observability").toFile()

        val initial = GraphObservability.open(reportDir, "observabilitySmoke")
        val replay = GraphObservability.open(
            reportDir,
            "observabilitySmoke",
            requireExistingCarrier = true,
        )

        assertTrue(initial.traceId.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(initial.traceId, replay.traceId)
        assertEquals(initial.carrier, replay.carrier)
        assertTrue(initial.carrier.getValue("traceparent").contains(initial.traceId))
        assertTrue(reportDir.resolve("trace-context.json").isFile)

        val spec = ValidationNodeSpec(
            id = "probe",
            kind = com.hayden.testgraphsdk.NodeKind.EVIDENCE,
            runtime = ValidationRuntime.Uv(reportDir.resolve("probe.py").absolutePath),
            dependsOn = emptyList(),
            tags = emptySet(),
            timeout = "30s",
            retries = 0,
            cacheable = false,
            sideEffects = emptySet(),
            inputs = emptyMap(),
            outputs = emptyMap(),
            rerun = true,
        )
        initial.nodeLaunch(spec, 1)
        initial.nodeResult(spec, "passed", java.time.Duration.ofMillis(2))

        val elapsed = measureTimeMillis { initial.finish("passed", timeoutMillis = 10) }
        assertTrue(elapsed < 1_000, "terminal flush must remain bounded")
    }

    @Test
    fun resumeRequiresAnExistingTraceCarrier() {
        disableExportForUnitTest()
        val reportDir = Files.createTempDirectory("test-graph-missing-carrier").toFile()

        val failure = assertFailsWith<IllegalArgumentException> {
            GraphObservability.open(
                reportDir,
                "resumeMissingCarrier",
                requireExistingCarrier = true,
            )
        }

        assertTrue(failure.message.orEmpty().contains("resume requires an existing trace carrier"))
        assertTrue(!reportDir.resolve("trace-context.json").exists())
    }

    @Test
    fun invalidExistingTraceCarrierFailsClosedWithoutReplacement() {
        disableExportForUnitTest()
        val reportDir = Files.createTempDirectory("test-graph-invalid-carrier").toFile()
        val carrier = reportDir.resolve("trace-context.json")
        carrier.writeText("{not-json")

        assertFailsWith<IllegalArgumentException> {
            GraphObservability.open(reportDir, "invalidCarrier")
        }

        assertEquals("{not-json", carrier.readText())
    }

    @Test
    fun oversizedExistingTraceCarrierIsRejectedBeforeReading() {
        disableExportForUnitTest()
        val reportDir = Files.createTempDirectory("test-graph-oversized-carrier").toFile()
        val carrier = reportDir.resolve("trace-context.json")
        RandomAccessFile(carrier, "rw").use {
            it.setLength(GraphObservability.TRACE_CARRIER_MAX_UTF8_BYTES.toLong() + 1)
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            GraphObservability.open(reportDir, "oversizedCarrier")
        }

        assertTrue(
            failure.message.orEmpty().contains(
                "exceeds ${GraphObservability.TRACE_CARRIER_MAX_UTF8_BYTES} UTF-8 bytes"
            )
        )
    }

    private fun disableExportForUnitTest() {
        System.setProperty("otel.traces.exporter", "none")
        System.setProperty("otel.metrics.exporter", "none")
        System.setProperty("otel.logs.exporter", "none")
    }
}

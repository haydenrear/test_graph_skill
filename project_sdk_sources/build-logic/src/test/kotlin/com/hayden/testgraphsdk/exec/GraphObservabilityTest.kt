package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphObservabilityTest {

    @Test
    fun unavailableColdCollectorBeforeFirstNodeRemainsFailOpen() {
        pointExportersAtUnavailableCollector()
        val reportDir = Files.createTempDirectory("test-graph-observability").toFile()

        val openStarted = System.nanoTime()
        val initial = GraphObservability.open(reportDir, "observabilitySmoke")
        val openElapsedMillis = (System.nanoTime() - openStarted) / 1_000_000
        val replay = GraphObservability.open(reportDir, "observabilitySmoke")

        assertTrue(
            openElapsedMillis < 2_000,
            "graph-start telemetry must not wait for an unavailable cold collector",
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

    private fun pointExportersAtUnavailableCollector() {
        System.setProperty("otel.traces.exporter", "otlp")
        System.setProperty("otel.metrics.exporter", "otlp")
        System.setProperty("otel.logs.exporter", "otlp")
        System.setProperty("otel.exporter.otlp.protocol", "http/protobuf")
        System.setProperty("otel.exporter.otlp.endpoint", "http://127.0.0.1:1")
    }
}

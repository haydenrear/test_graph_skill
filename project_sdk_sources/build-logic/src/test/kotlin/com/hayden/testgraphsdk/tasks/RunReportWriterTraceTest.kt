package com.hayden.testgraphsdk.tasks

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains

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
}

package com.hayden.testgraphsdk.exec

import java.nio.file.Files
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanExecutorResumeHarnessTest {

    @Test
    fun readsSavedInputContextSnapshot() {
        val reportRoot = Files.createTempDirectory("test-graph-resume").toFile()
        val input = listOf(
            ContextItem("app.running", mapOf("ready" to "true")),
            ContextItem("user.seeded", mapOf("userId" to "demo-user")),
        )

        writeInputContextSnapshot(input, reportRoot, "login.smoke")

        assertEquals(input, readInputContextSnapshot(reportRoot, "login.smoke"))
    }

    @Ignore("Harness placeholder for TG-3C/TG-3D integration-level executor tests.")
    @Test
    fun resumePlanExecutesFromSelectedNodeAndContinuesDownstream() {
    }

    @Ignore("Harness placeholder for validating dependency coverage in saved input context.")
    @Test
    fun resumeRejectsMissingDependencyContext() {
    }

    @Ignore("Harness placeholder for validating rerun=false selected-node rejection.")
    @Test
    fun resumeRejectsSelectedRerunDisabledNode() {
    }
}

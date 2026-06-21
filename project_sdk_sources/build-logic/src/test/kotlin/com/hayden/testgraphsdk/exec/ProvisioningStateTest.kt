package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.NodeKind
import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProvisioningStateTest {

    @Test
    fun provisionWritesBranchScopedMarker() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(projectDir)
        val spec = node("environment.provision", "environment:provision")
        val prepared = state.prepare(spec)
        val record = state.recordSuccessful(spec, prepared, "passed")

        val marker = assertNotNull(record?.provisionedMarker)
        assertTrue(marker.isFile)
        val body = marker.readText()
        assertTrue(body.contains("\"environmentId\": \"branch-environment__feature-a__local-preview__local\""))
        assertTrue(body.contains("\"state\": \"provisioned\""))
    }

    @Test
    fun failedProvisionDoesNotWriteMarker() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(projectDir)
        val spec = node("environment.provision", "environment:provision")
        val prepared = state.prepare(spec)

        val record = state.recordSuccessful(spec, prepared, "failed")

        assertTrue(record == null)
        assertFalse(
            projectDir.resolve("build/testgraph-provisioning-state/provisioned")
                .walkTopDown()
                .any { it.isFile }
        )
    }

    @Test
    fun resetKeepsProvisionedMarker() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(projectDir)
        val provision = node("environment.provision", "environment:provision")
        val deploy = node("environment.deploy", "environment:deploy")
        val reset = node("environment.reset", "environment:reset")

        val provisionRecord = state.recordSuccessful(provision, state.prepare(provision), "passed")
        val provisionedMarker = assertNotNull(provisionRecord?.provisionedMarker)
        val deployRecord = state.recordSuccessful(deploy, state.prepare(deploy), "passed")
        val deployedMarker = assertNotNull(deployRecord?.deployedMarker)
        val resetRecord = state.recordSuccessful(reset, state.prepare(reset), "passed")

        assertTrue(provisionedMarker.isFile)
        assertFalse(deployedMarker.exists())
        assertTrue(assertNotNull(resetRecord?.resetMarker).isFile)
        assertTrue(provisionedMarker.isFile)
    }

    @Test
    fun destroyRequiresExplicitIntentAndRemovesProvisionedMarkerOnlyOnSuccess() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val provisionState = state(projectDir)
        val provision = node("environment.provision", "environment:provision")
        val provisionRecord = provisionState.recordSuccessful(provision, provisionState.prepare(provision), "passed")
        val marker = assertNotNull(provisionRecord?.provisionedMarker)
        val deploy = node("environment.deploy", "environment:deploy")
        val deployRecord = provisionState.recordSuccessful(deploy, provisionState.prepare(deploy), "passed")
        val deployedMarker = assertNotNull(deployRecord?.deployedMarker)

        assertFailsWith<IllegalArgumentException> {
            provisionState.prepare(node("environment.destroy", "environment:destroy"))
        }
        assertTrue(marker.isFile)
        assertTrue(deployedMarker.isFile)

        val destroyState = state(
            projectDir,
            env = baseEnv() + ("TESTGRAPH_DESTROY_BRANCH_ENVIRONMENT" to "true"),
        )
        val destroy = node("environment.destroy", "environment:destroy")
        val prepared = destroyState.prepare(destroy)
        val failedDestroy = destroyState.recordSuccessful(destroy, prepared, "failed")

        assertTrue(failedDestroy == null)
        assertTrue(marker.isFile)

        val destroyRecord = assertNotNull(destroyState.recordSuccessful(destroy, prepared, "passed"))
        assertNotNull(destroyRecord.destroyRequestMarker)
        assertNotNull(destroyRecord.destroyedMarker)
        assertFalse(marker.exists())
        assertFalse(deployedMarker.exists())
    }

    @Test
    fun awsTargetRequiresCredentials() {
        val projectDir = Files.createTempDirectory("test-graph-provisioning").toFile()
        val state = state(
            projectDir,
            env = baseEnv() + mapOf("TEST_GRAPH_ENVIRONMENT_TARGET" to "aws-preview"),
        )

        assertFailsWith<IllegalArgumentException> {
            state.prepare(node("environment.provision", "environment:provision"))
        }

        val credentialed = state(
            projectDir,
            env = baseEnv() + mapOf(
                "TEST_GRAPH_ENVIRONMENT_TARGET" to "aws-preview",
                "AWS_PROFILE" to "test",
            ),
        )

        assertNotNull(credentialed.prepare(node("environment.provision", "environment:provision")))
    }

    private fun state(
        projectDir: java.io.File,
        env: Map<String, String> = baseEnv(),
    ): ProvisioningState =
        ProvisioningState(
            projectDir = projectDir,
            graphName = "branch-environment",
            runId = "run-1",
            env = env,
        )

    private fun baseEnv(): Map<String, String> =
        mapOf("TEST_GRAPH_FEATURE_BRANCH" to "feature-a")

    private fun node(id: String, vararg sideEffects: String): ValidationNodeSpec =
        ValidationNodeSpec(
            id = id,
            kind = NodeKind.ACTION,
            runtime = ValidationRuntime.Uv("sources/$id.py"),
            sideEffects = sideEffects.toSet(),
        )
}

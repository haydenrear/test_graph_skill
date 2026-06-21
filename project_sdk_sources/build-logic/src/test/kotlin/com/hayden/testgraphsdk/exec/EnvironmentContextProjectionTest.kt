package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.SideEffectSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentContextProjectionTest {

    @Test
    fun projectsExplicitEnvironmentKeysFromLatestContext() {
        val items = listOf(
            ContextItem("provision.one", mapOf("KUBECONFIG" to "/old", "ignored-key" to "x")),
            ContextItem("provision.two", mapOf("KUBECONFIG" to "/new", "KUBECONTEXT" to "ctx")),
        )

        val projected = EnvironmentContextProjection.project(
            items,
            SideEffectSpec.parseAll(listOf("env:[KUBECONFIG]")),
        )

        assertEquals(mapOf("KUBECONFIG" to "/new"), projected)
    }

    @Test
    fun projectsAllEligibleEnvironmentKeys() {
        val items = listOf(
            ContextItem("provision", mapOf("EnvironmentId" to "env-1", "KUBECONFIG" to "/tmp/kube", "bad-key" to "no")),
        )

        val projected = EnvironmentContextProjection.project(
            items,
            SideEffectSpec.parseAll(listOf("env:[*]")),
        )

        assertEquals(
            mapOf("EnvironmentId" to "env-1", "KUBECONFIG" to "/tmp/kube"),
            projected,
        )
    }
}

package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.EnvironmentRepositorySpec
import com.hayden.testgraphsdk.NodeKind
import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.ValidationRuntime
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentRepositoryRuntimeTest {

    @Test
    fun provisionsThenReusesGeneratedGitEnvironmentRepository() {
        val projectDir = Files.createTempDirectory("test-graph-env-runtime").toFile()
        val reportRoot = File(projectDir, "build/reports/run-1").apply { mkdirs() }
        val source = createEnvironmentRepository(projectDir)
        val state = ProvisioningState(
            projectDir = projectDir,
            graphName = "environmentRepositoryContract",
            runId = "run-1",
            env = mapOf("TEST_GRAPH_FEATURE_BRANCH" to "feature-a"),
        )
        val runtime = EnvironmentRepositoryRuntime(projectDir, reportRoot, state, env = emptyMap())

        val provision = node("environment.provision", source)
        val prepared = state.prepare(provision)
        val first = runtime.execute(provision, prepared)
            ?: error("expected environment repository execution")
        state.recordSuccessful(provision, prepared, "passed")

        assertFalse(first.reused)
        assertTrue(first.commands.any { it.label == "tofu-apply" })
        assertEquals(first.identity.id, first.outputs["EnvironmentId"])
        assertEquals("test-graph-feature-a", first.outputs["KUBECONTEXT"])

        val reuse = node("environment.reuse", source)
        val second = runtime.execute(reuse, state.prepare(reuse))
            ?: error("expected environment repository reuse")

        assertTrue(second.reused)
        assertFalse(second.commands.any { it.label == "tofu-apply" })
        assertEquals(first.outputs["KUBECONFIG"], second.outputs["KUBECONFIG"])

        val deploy = node("environment.deploy", source, "environment:deploy")
        val deployed = runtime.execute(deploy, state.prepare(deploy))
            ?: error("expected environment repository deploy reuse")

        assertTrue(deployed.reused)
        assertFalse(deployed.commands.any { it.label == "tofu-apply" })
        assertEquals(first.outputs["KUBECONFIG"], deployed.outputs["KUBECONFIG"])

        val reset = node("environment.reset", source, "environment:reset")
        val resetExecution = runtime.execute(reset, state.prepare(reset))
            ?: error("expected environment repository reset")

        assertFalse(resetExecution.reused)
        assertTrue(resetExecution.commands.any { it.label == "tofu-apply" })
    }

    @Test
    fun passesTargetAndBackendToOpenTofuVariables() {
        val projectDir = Files.createTempDirectory("test-graph-env-runtime-target").toFile()
        val reportRoot = File(projectDir, "build/reports/run-1").apply { mkdirs() }
        val source = createEnvironmentRepository(projectDir)
        val state = ProvisioningState(
            projectDir = projectDir,
            graphName = "environmentRepositoryContract",
            runId = "run-1",
            env = mapOf("TEST_GRAPH_FEATURE_BRANCH" to "feature-a"),
        )
        val runtime = EnvironmentRepositoryRuntime(projectDir, reportRoot, state, env = emptyMap())

        val provision = node(
            "environment.provision.github",
            source,
            repository = EnvironmentRepositorySpec(
                source = source.absolutePath,
                template = "templates/local-preview",
                target = "local-github-action",
                backend = "github-action",
                outputKeys = setOf("EnvironmentId", "KUBECONFIG", "KUBECONTEXT", "TARGET", "BACKEND"),
            ),
        )
        val execution = runtime.execute(provision, state.prepare(provision))
            ?: error("expected environment repository execution")

        assertEquals("local-github-action", execution.outputs["TARGET"])
        assertEquals("github-action", execution.outputs["BACKEND"])
    }

    private fun node(
        id: String,
        source: File,
        sideEffect: String = "environment:provision",
        repository: EnvironmentRepositorySpec = EnvironmentRepositorySpec(
            source = source.absolutePath,
            template = "templates/local-preview",
        ),
    ): ValidationNodeSpec =
        ValidationNodeSpec(
            id = id,
            kind = NodeKind.ACTION,
            runtime = ValidationRuntime.Uv("sources/$id.py"),
            sideEffects = setOf(sideEffect),
            environmentRepository = repository,
        )

    private fun createEnvironmentRepository(projectDir: File): File {
        val repo = File(projectDir, "build/generated-env-source").apply { mkdirs() }
        File(repo, "templates/local-preview").mkdirs()
        File(repo, "templates/local-preview/main.tf").writeText("terraform {}\n")
        File(repo, "templates/local-preview/variables.tf").writeText("variable \"environment_id\" { type = string }\n")
        File(repo, "templates/local-preview/outputs.tf").writeText("output \"EnvironmentId\" { value = var.environment_id }\n")
        val tofu = File(repo, "bin/tofu").apply {
            parentFile.mkdirs()
            writeText(
                """
                #!/usr/bin/env sh
                set -eu
                cmd="${'$'}1"
                shift || true
                case "${'$'}cmd" in
                  init)
                    mkdir -p .terraform
                    ;;
                  apply)
                    mkdir -p generated
                    printf 'kubeconfig for %s\n' "${'$'}TF_VAR_environment_id" > generated/kubeconfig
                    ;;
                  output)
                    cat <<JSON
                {"EnvironmentId":{"sensitive":false,"type":"string","value":"${'$'}TF_VAR_environment_id"},"KUBECONFIG":{"sensitive":false,"type":"string","value":"${'$'}PWD/generated/kubeconfig"},"KUBECONTEXT":{"sensitive":false,"type":"string","value":"test-graph-${'$'}TF_VAR_branch"},"TARGET":{"sensitive":false,"type":"string","value":"${'$'}TF_VAR_target"},"BACKEND":{"sensitive":false,"type":"string","value":"${'$'}TF_VAR_backend"}}
                JSON
                    ;;
                  destroy)
                    rm -rf generated .terraform
                    ;;
                  *)
                    exit 64
                    ;;
                esac
                """.trimIndent()
            )
            setExecutable(true)
        }
        check(tofu.canExecute())
        git(repo, "init")
        git(repo, "config", "user.email", "test-graph@example.invalid")
        git(repo, "config", "user.name", "Test Graph")
        git(repo, "add", ".")
        git(repo, "commit", "-m", "Initial environment")
        return repo
    }

    private fun git(repo: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) {
            "git ${args.joinToString(" ")} failed: $output"
        }
    }
}

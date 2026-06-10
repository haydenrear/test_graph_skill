package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.GraphAssembler
import com.hayden.testgraphsdk.TestGraphSpec
import com.hayden.testgraphsdk.Toolchain
import com.hayden.testgraphsdk.exec.ExecutorRegistry
import com.hayden.testgraphsdk.exec.PlanExecutor
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object RunIds {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    fun next(): String = fmt.format(Instant.now())
}

/**
 * Executes one test graph. Registered by the extension as a task named
 * after the graph (so `testGraph("smoke")` ⇒ `./gradlew smoke`).
 *
 * Non-Property internal fields are set at configuration time by the
 * extension. This avoids reshaping the task's Property<T> surface for
 * every new field while we iterate on the DSL.
 */
abstract class RunTestGraphTask : DefaultTask() {

    @get:Internal lateinit var graphSpec: TestGraphSpec

    /** Late-bound so `sourcesDir(...)` calls after `testGraph(...)` still count. */
    @get:Internal lateinit var sourcesDirsProvider: () -> List<File>

    @get:Internal abstract val projectDirectory: DirectoryProperty
    @get:Internal abstract val reportRoot: DirectoryProperty
    @get:Internal var resumeFromBuildPath: String? = null
    @get:Internal var resumeFromNodeId: String? = null

    init {
        group = "validation"
        description = "Execute this test graph."
    }

    @Option(
        option = "resume-from-build",
        description = "Existing build/validation-reports/<runId> directory to resume from.",
    )
    fun setResumeFromBuild(path: String) {
        resumeFromBuildPath = path
    }

    @Option(
        option = "resume-from-node",
        description = "Node id whose saved input context should seed resumed graph execution.",
    )
    fun setResumeFromNode(nodeId: String) {
        resumeFromNodeId = nodeId
    }

    @TaskAction
    fun run() {
        val projDir = projectDirectory.get().asFile
        val tools = Toolchain.resolve(project)
        val plan = GraphAssembler.plan(graphSpec, sourcesDirsProvider(), projDir, tools)
        val resume = resumeRequest()
        val runId = resume?.buildDir?.name ?: RunIds.next()
        val reportDir = if (resume == null) {
            reportRoot.dir(runId).get()
        } else {
            project.layout.dir(project.provider { resume.buildDir }).get()
        }
        reportDir.asFile.mkdirs()

        logger.lifecycle("testGraph '${graphSpec.name}' run=$runId steps=${plan.size}")
        for ((i, n) in plan.withIndex()) {
            logger.lifecycle(
                "  plan[${i + 1}/${plan.size}] ${n.id}  [${n.kind.name.lowercase()}, ${n.runtime.name}]  ${n.runtime.entryFile}"
            )
        }

        PlanExecutor(
            ExecutorRegistry.defaults(tools),
            projectDirectory.get(), reportDir, runId, logger,
        ).run(plan, resume)

        // Roll this graph's per-node envelopes into summary.json + report.md
        // right here, while we still own this run dir. Doing it inline avoids
        // the Gradle finalizer dedup that left some run dirs without a
        // report when one `validationReport` finalizer was shared across
        // multiple graph tasks (smoke, sponsored, ...) inside a single
        // `validationRunAll` invocation.
        if (RunReportWriter.writeRunReport(reportDir.asFile)) {
            logger.lifecycle(
                "wrote ${File(reportDir.asFile, "summary.json").absolutePath} + " +
                        "${File(reportDir.asFile, "report.md").absolutePath}"
            )
        }

        logger.lifecycle("testGraph '${graphSpec.name}' done. reports: ${reportDir.asFile.absolutePath}")
    }

    private fun resumeRequest(): PlanExecutor.ResumeFromBuild? {
        val buildPath = resumeFromBuildPath
        val nodeId = resumeFromNodeId
        if ((buildPath == null) != (nodeId == null)) {
            throw IllegalArgumentException(
                "--resume-from-build and --resume-from-node must be provided together"
            )
        }
        if (buildPath == null || nodeId == null) return null

        val buildDir = File(buildPath).let {
            if (it.isAbsolute) it else File(project.projectDir, buildPath)
        }.canonicalFile
        if (!buildDir.isDirectory) {
            throw IllegalArgumentException(
                "--resume-from-build must point at an existing run directory: ${buildDir.absolutePath}"
            )
        }
        return PlanExecutor.ResumeFromBuild(buildDir = buildDir, nodeId = nodeId)
    }
}

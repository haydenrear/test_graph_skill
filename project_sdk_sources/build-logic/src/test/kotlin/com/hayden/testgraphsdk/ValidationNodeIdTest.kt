package com.hayden.testgraphsdk

import com.hayden.testgraphsdk.exec.ContextItem
import com.hayden.testgraphsdk.exec.ContextSerde
import com.hayden.testgraphsdk.exec.inputContextSnapshotFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidationNodeIdTest {

    @Test
    fun rejectsFilesystemUnsafeAndPreviouslyCollidingNodeIds() {
        val reportRoot = Files.createTempDirectory("test-graph-node-id").toFile()
        for (nodeId in listOf("a:b", "a?b", "../", "safe\n", "Node")) {
            assertFailsWith<IllegalArgumentException> {
                ValidationNodeSpec(
                    id = nodeId,
                    kind = NodeKind.ASSERTION,
                    runtime = ValidationRuntime.Uv("node.py"),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                inputContextSnapshotFile(reportRoot, nodeId)
            }
        }
    }

    /**
     * The refusal has to name the value AND the script. A graph is many nodes;
     * "node id must match [a-z0-9._-]{1,128}" on its own identifies none of
     * them, and the reader opens every script in `sources/` to find the one
     * that is wrong.
     */
    @Test
    fun rejectionNamesTheOffendingIdAndTheScriptThatDeclaredIt() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ValidationNodeSpec(
                id = "spec.External-Cases",
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("sources/run_external_cases.py"),
            )
        }
        val message = failure.message.orEmpty()

        assertTrue(
            "spec.External-Cases" in message,
            "the rejected id is in the message: $message",
        )
        assertTrue(
            "sources/run_external_cases.py" in message,
            "the script that declared it is in the message: $message",
        )
        assertTrue(
            "[a-z0-9._-]{1,128}" in message,
            "the grammar is still stated: $message",
        )
    }

    /**
     * The over-long id is the case where the rendered value alone misleads: 129
     * characters of `a` look perfectly well-formed, so the length has to be
     * stated or the reader concludes the grammar is broken.
     */
    @Test
    fun rejectionStatesTheLengthOfAnOverlongId() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ValidationNodeSpec(
                id = "a".repeat(129),
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("sources/long.py"),
            )
        }

        assertTrue(
            "(length 129)" in failure.message.orEmpty(),
            "the true length is stated: ${failure.message}",
        )
    }

    /**
     * A raw control character pasted into an error message hides the very
     * defect the message is reporting — `"safe\n"` and `"safe"` print
     * identically once the newline is real.
     */
    @Test
    fun rejectionEscapesInvisibleCharactersInTheOffendingId() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ValidationNodeSpec(
                id = "safe\n",
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("sources/newline.py"),
            )
        }
        val message = failure.message.orEmpty()

        assertTrue("safe\\n" in message, "the newline is escaped, not printed: $message")
        assertTrue('\n' !in message, "the message stays on one line: $message")
    }

    /** Labelled call sites keep their label and gain the rejected value. */
    @Test
    fun labelledRejectionKeepsTheLabelAndNamesTheValue() {
        val failure = assertFailsWith<IllegalArgumentException> {
            ContextItem(nodeId = "../", data = emptyMap())
        }
        val message = failure.message.orEmpty()

        assertTrue("context nodeId" in message, "the label survives: $message")
        assertTrue("../" in message, "the rejected value is named: $message")
    }

    @Test
    fun rejectsUnsafeNodeIdsImportedFromSavedContext() {
        assertFailsWith<IllegalArgumentException> {
            ContextSerde.fromJson(
                """{"items":[{"nodeId":"../","data":{"value":"unsafe"}}]}"""
            )
        }
    }

    @Test
    fun acceptsOnlyTheBoundedAsciiFilesystemSafeGrammar() {
        val lowercase = ValidationNodeSpec(
            id = "node",
            kind = NodeKind.ASSERTION,
            runtime = ValidationRuntime.Uv("node.py"),
        )
        val valid = "a".repeat(128)
        val spec = ValidationNodeSpec(
            id = valid,
            kind = NodeKind.ASSERTION,
            runtime = ValidationRuntime.Uv("node.py"),
        )

        assertEquals("node", lowercase.id)
        assertEquals(valid, spec.id)
        assertFailsWith<IllegalArgumentException> {
            ValidationNodeSpec(
                id = "a".repeat(129),
                kind = NodeKind.ASSERTION,
                runtime = ValidationRuntime.Uv("node.py"),
            )
        }
    }
}

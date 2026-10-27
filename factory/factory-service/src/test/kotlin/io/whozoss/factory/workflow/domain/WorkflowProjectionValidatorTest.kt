package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests of the multi-lane additions (W8.4) to [WorkflowProjectionValidator].
 *
 * The validator stays backward-compatible: `lane`, `startedAt`, `completedAt` and
 * `durationMs` are optional pass-through attributes; `lane` is derived from the
 * responsibility kind when absent; unknown fields are still rejected.
 */
class WorkflowProjectionValidatorTest {

    private fun projection(steps: List<Map<String, Any?>>): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to "2",
        "workflowId" to "wf-1",
        "workflowType" to "delivery",
        "title" to "Delivery",
        "status" to "running",
        "steps" to steps,
    )

    private fun normalizedSteps(input: Map<String, Any?>): List<Map<String, Any?>> {
        val validation = WorkflowProjectionValidator.validate(input, "wf-1")
        assertThat(validation).isInstanceOf(ProjectionValidation.Valid::class.java)
        @Suppress("UNCHECKED_CAST")
        return (validation as ProjectionValidation.Valid).normalized["steps"] as List<Map<String, Any?>>
    }

    @Test
    fun `derives the lane from the responsibility kind and passes the execution window through`() {
        val steps = normalizedSteps(
            projection(
                listOf(
                    linkedMapOf(
                        "id" to "h",
                        "name" to "Human gate",
                        "status" to "completed",
                        "responsibility" to linkedMapOf("kind" to "human", "name" to "Product owner"),
                        "startedAt" to "2026-09-27T10:00:00.000Z",
                        "completedAt" to "2026-09-27T10:00:01.000Z",
                        "durationMs" to 1000,
                    ),
                ),
            ),
        )

        val step = steps.single()
        assertThat(step["lane"]).isEqualTo("human")
        assertThat(step["startedAt"]).isEqualTo("2026-09-27T10:00:00.000Z")
        assertThat(step["completedAt"]).isEqualTo("2026-09-27T10:00:01.000Z")
        assertThat(step["durationMs"]).isEqualTo(1000L)
        @Suppress("UNCHECKED_CAST")
        val responsibility = step["responsibility"] as Map<String, Any?>
        assertThat(responsibility["kind"]).isEqualTo("human")
        assertThat(responsibility["name"]).isEqualTo("Product owner")
    }

    @Test
    fun `an explicit lane wins over the responsibility kind`() {
        val steps = normalizedSteps(
            projection(
                listOf(
                    linkedMapOf(
                        "id" to "c",
                        "name" to "Verification",
                        "status" to "pending",
                        "lane" to "code",
                        "responsibility" to linkedMapOf("kind" to "agent", "name" to "worker"),
                    ),
                ),
            ),
        )
        assertThat(steps.single()["lane"]).isEqualTo("code")
    }

    @Test
    fun `rejects an unknown lane value and still rejects unknown step fields`() {
        val badLane = WorkflowProjectionValidator.validate(
            projection(
                listOf(
                    linkedMapOf(
                        "id" to "x",
                        "name" to "X",
                        "status" to "pending",
                        "lane" to "nope",
                        "responsibility" to linkedMapOf("kind" to "agent"),
                    ),
                ),
            ),
            "wf-1",
        )
        assertThat(badLane).isInstanceOf(ProjectionValidation.Invalid::class.java)

        val unknownField = WorkflowProjectionValidator.validate(
            projection(
                listOf(
                    linkedMapOf(
                        "id" to "x",
                        "name" to "X",
                        "status" to "pending",
                        "bogus" to "nope",
                        "responsibility" to linkedMapOf("kind" to "agent"),
                    ),
                ),
            ),
            "wf-1",
        )
        assertThat(unknownField).isInstanceOf(ProjectionValidation.Invalid::class.java)
    }
}

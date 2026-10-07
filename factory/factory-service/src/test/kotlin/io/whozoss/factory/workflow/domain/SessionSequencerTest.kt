package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of the session DAG evaluation (no Spring, no database).
 *
 * Covers the failure rule: a satisfied dependency releases a step, a failed or
 * blocked dependency blocks it (transitively), a human suspension is not
 * terminal, and the session status is derived from the step states.
 */
class SessionSequencerTest {

    private fun step(id: String, vararg dependsOn: String, kind: ResponsibilityKind = ResponsibilityKind.CODE) =
        WorkflowStepDefinition(
            id = id,
            name = "Step $id",
            responsibility = WorkflowStepResponsibility(kind, "name-$id"),
            dependsOn = dependsOn.toList(),
        )

    private fun statuses(vararg pairs: Pair<String, String>): LinkedHashMap<String, String> =
        LinkedHashMap(pairs.toMap())

    @Test
    fun `initial statuses mark root steps ready and dependents pending`() {
        val steps = listOf(step("a"), step("b", "a"))

        val initial = SessionSequencer.initialStatuses(steps)

        assertThat(initial["a"]).isEqualTo(WorkflowStatuses.READY)
        assertThat(initial["b"]).isEqualTo(WorkflowStatuses.PENDING)
    }

    @Test
    fun `a pending step becomes ready only when all its dependencies completed`() {
        val steps = listOf(step("a"), step("b", "a"), step("c", "a", "b"))

        val evaluation = SessionSequencer.evaluate(
            steps,
            statuses("a" to WorkflowStatuses.COMPLETED, "b" to WorkflowStatuses.PENDING, "c" to WorkflowStatuses.PENDING),
        )

        assertThat(evaluation.ready).containsExactly("b")
        assertThat(evaluation.blocked).isEmpty()
    }

    @Test
    fun `a failed dependency blocks its dependent and propagates transitively`() {
        val steps = listOf(step("a"), step("b", "a"), step("c", "b"))

        val first = SessionSequencer.evaluate(
            steps,
            statuses("a" to WorkflowStatuses.FAILED, "b" to WorkflowStatuses.PENDING, "c" to WorkflowStatuses.PENDING),
        )
        assertThat(first.blocked).containsExactly("b")

        val second = SessionSequencer.evaluate(
            steps,
            statuses("a" to WorkflowStatuses.FAILED, "b" to WorkflowStatuses.BLOCKED, "c" to WorkflowStatuses.PENDING),
        )
        assertThat(second.blocked).containsExactly("c")
    }

    @Test
    fun `a blocked dependency takes precedence over a satisfied one`() {
        val steps = listOf(step("a", kind = ResponsibilityKind.CODE), step("b"), step("c", "a", "b"))

        val evaluation = SessionSequencer.evaluate(
            steps,
            statuses("a" to WorkflowStatuses.FAILED, "b" to WorkflowStatuses.COMPLETED, "c" to WorkflowStatuses.PENDING),
        )

        assertThat(evaluation.blocked).containsExactly("c")
        assertThat(evaluation.ready).isEmpty()
    }

    @Test
    fun `terminal status is completed when every step completed`() {
        val steps = listOf(step("a"), step("b", "a"))

        val status = SessionSequencer.terminalStatus(
            steps,
            statuses("a" to WorkflowStatuses.COMPLETED, "b" to WorkflowStatuses.COMPLETED),
        )

        assertThat(status).isEqualTo(WorkflowStatuses.COMPLETED)
    }

    @Test
    fun `terminal status is failed when a branch failed or was blocked`() {
        val steps = listOf(step("a"), step("b", "a"), step("c"), step("d", "c"))

        val status = SessionSequencer.terminalStatus(
            steps,
            statuses(
                "a" to WorkflowStatuses.COMPLETED,
                "b" to WorkflowStatuses.COMPLETED,
                "c" to WorkflowStatuses.FAILED,
                "d" to WorkflowStatuses.BLOCKED,
            ),
        )

        assertThat(status).isEqualTo(WorkflowStatuses.FAILED)
    }

    @Test
    fun `terminal status is waiting_human when a step is suspended`() {
        val steps = listOf(step("a"), step("b", "a", kind = ResponsibilityKind.HUMAN))

        val status = SessionSequencer.terminalStatus(
            steps,
            statuses("a" to WorkflowStatuses.COMPLETED, "b" to WorkflowStatuses.WAITING_HUMAN),
        )

        assertThat(status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
    }

    @Test
    fun `readySteps lists the runnable steps in definition order`() {
        val steps = listOf(step("a"), step("b"), step("c", "a"))

        val ready = SessionSequencer.readySteps(
            steps,
            statuses("a" to WorkflowStatuses.READY, "b" to WorkflowStatuses.READY, "c" to WorkflowStatuses.PENDING),
        )

        assertThat(ready).containsExactly("a", "b")
    }
}

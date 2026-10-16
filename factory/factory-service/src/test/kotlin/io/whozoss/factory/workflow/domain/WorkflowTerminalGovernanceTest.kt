package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Phase 10 pure-domain attestation of the workflow terminal vocabulary of
 * `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowModels.kt`,
 * the `WORKFLOW_SEALED` machine code of
 * `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowExceptions.kt`
 * and the linked-successor relations of
 * `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowInstance.kt`.
 */
class WorkflowTerminalGovernanceTest {

    @Test
    fun `the terminal run statuses are exactly completed failed and cancelled`() {
        assertThat(WorkflowStatuses.TERMINAL).containsExactlyInAnyOrder(
            WorkflowStatuses.COMPLETED,
            WorkflowStatuses.FAILED,
            WorkflowStatuses.CANCELLED,
        )
        assertThat(WorkflowStatuses.isTerminal(WorkflowStatuses.COMPLETED)).isTrue()
        assertThat(WorkflowStatuses.isTerminal(WorkflowStatuses.FAILED)).isTrue()
        assertThat(WorkflowStatuses.isTerminal(WorkflowStatuses.CANCELLED)).isTrue()
        assertThat(WorkflowStatuses.isTerminal(WorkflowStatuses.PENDING)).isFalse()
        assertThat(WorkflowStatuses.isTerminal(WorkflowStatuses.READY)).isFalse()
        assertThat(WorkflowStatuses.isTerminal(WorkflowStatuses.RUNNING)).isFalse()
        assertThat(WorkflowStatuses.isTerminal(WorkflowStatuses.WAITING_HUMAN)).isFalse()
        assertThat(WorkflowStatuses.isTerminal(WorkflowStatuses.BLOCKED)).isFalse()
        assertThat(WorkflowStatuses.isTerminal(null)).isFalse()
        assertThat(WorkflowStatuses.isTerminal("unknown")).isFalse()
    }

    @Test
    fun `the step state machine already gives terminal statuses an empty outgoing set`() {
        WorkflowStatuses.TERMINAL.forEach { status ->
            assertThat(WorkflowStatuses.TRANSITIONS[status]).isEmpty()
        }
    }

    @Test
    fun `a sealed workflow rejection maps to http 409`() {
        assertThat(workflowStatusCode(WorkflowErrorCodes.WORKFLOW_SEALED)).isEqualTo(409)
        val exception = workflowException(WorkflowErrorCodes.WORKFLOW_SEALED)
        assertThat(exception.errorCode).isEqualTo("WORKFLOW_SEALED")
        assertThat(exception.statusCode).isEqualTo(409)
    }

    @Test
    fun `a linked successor carries the previous and root workflow ids and never reopens the predecessor`() {
        val relations = linkedWorkflowRelations("wf-new", "wf-previous")
        assertThat(relations).containsExactlyEntriesOf(
            mapOf(
                "rootWorkflowId" to "wf-previous",
                "previousWorkflowId" to "wf-previous",
            ),
        )

        // A longer run chain keeps the original root.
        val chained = linkedWorkflowRelations("wf-third", "wf-second", rootWorkflowId = "wf-first")
        assertThat(chained).containsExactlyEntriesOf(
            mapOf(
                "rootWorkflowId" to "wf-first",
                "previousWorkflowId" to "wf-second",
            ),
        )

        // An independent workflow stays its own root and carries no predecessor.
        assertThat(independentWorkflowRelations("wf-solo")).containsExactlyEntriesOf(mapOf("rootWorkflowId" to "wf-solo"))
    }
}

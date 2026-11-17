package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegacyStepContractTest {
    @Test
    fun `adoption exposes the same generic obligations used for dispatch`() {
        val step = WorkflowStepDefinition("work", "Analyze request", WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Dev"), emptyList())
        val metadata = LegacyStepContract.metadata(step)
        assertThat(metadata["adoptable"]).isEqualTo(true)
        assertThat(metadata).containsAllEntriesOf(LegacyStepContract.generic(step).toJson())
        assertThat(LegacyStepContract.generic(step).entry.joinToString()).contains("queryUser")
        assertThat(LegacyStepContract.generic(step.copy(dependsOn = listOf("plan"))).exit.joinToString()).contains("unchanged")
    }

    @Test
    fun `runtime-derived and non-agent steps expose explanations but no adoptable contract`() {
        for (responsibility in listOf(
            WorkflowStepResponsibility(ResponsibilityKind.AGENT, "Gitay"),
            WorkflowStepResponsibility(ResponsibilityKind.AGENT, "A11yReviewer"),
            WorkflowStepResponsibility(ResponsibilityKind.HUMAN, "PO"),
            WorkflowStepResponsibility(ResponsibilityKind.CODE, "test"),
        )) {
            val metadata = LegacyStepContract.metadata(WorkflowStepDefinition("step", "Work", responsibility, emptyList()))
            assertThat(metadata["adoptable"]).isEqualTo(false)
            assertThat(metadata["summary"] as String).isNotBlank()
            assertThat(metadata).doesNotContainKeys("entry", "mission", "exit")
        }
    }
}

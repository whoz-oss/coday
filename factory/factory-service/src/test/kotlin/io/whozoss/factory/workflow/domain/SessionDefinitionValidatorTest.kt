package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests of [SessionDefinitionValidator] — the pure DAG validation of a
 * declarative session (unique ids, existing dependencies, no self-dependency,
 * no cycle). These are framework-free unit tests.
 */
class SessionDefinitionValidatorTest {

    private fun step(id: String, dependsOn: List<String> = emptyList()): WorkflowStepDefinition =
        WorkflowStepDefinition(
            id = id,
            name = "Step $id",
            responsibility = WorkflowStepResponsibility(ResponsibilityKind.AGENT, "agent"),
            dependsOn = dependsOn,
        )

    private fun code(input: List<WorkflowStepDefinition>): String? =
        (SessionDefinitionValidator.validate(input) as? SessionDefinitionValidator.Result.Invalid)?.error?.code

    @Test
    fun `accepts an acyclic session`() {
        val steps = listOf(step("one"), step("two", listOf("one")), step("three", listOf("one", "two")))
        val result = SessionDefinitionValidator.validate(steps)
        assertThat(result).isInstanceOf(SessionDefinitionValidator.Result.Valid::class.java)
        assertThat((result as SessionDefinitionValidator.Result.Valid).steps).isEqualTo(steps)
    }

    @Test
    fun `rejects an empty session`() {
        assertThat(code(emptyList())).isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
    }

    @Test
    fun `rejects a blank step id`() {
        assertThat(code(listOf(step(" ")))).isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
    }

    @Test
    fun `rejects duplicate step ids`() {
        assertThat(code(listOf(step("one"), step("one"))))
            .isEqualTo(WorkflowDefinitionErrorCodes.DUPLICATE_STEP_ID)
    }

    @Test
    fun `rejects a dependency on an unknown step`() {
        assertThat(code(listOf(step("one", listOf("missing")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.MISSING_DEPENDENCY)
    }

    @Test
    fun `rejects a self dependency`() {
        assertThat(code(listOf(step("one", listOf("one")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.SELF_DEPENDENCY)
    }

    @Test
    fun `rejects a cycle`() {
        assertThat(code(listOf(step("one", listOf("two")), step("two", listOf("one")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.DEPENDENCY_CYCLE)
    }

    @Test
    fun `rejects a longer cycle`() {
        assertThat(
            code(listOf(step("one", listOf("three")), step("two", listOf("one")), step("three", listOf("two")))),
        ).isEqualTo(WorkflowDefinitionErrorCodes.DEPENDENCY_CYCLE)
    }
}

package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests of the declarative session format accepted by
 * [WorkflowDefinitionValidator] (W8.2).
 *
 * The format is intentionally minimal: a step is exactly
 * `id` + `name` + `dependsOn` + `responsibility { kind, name }`. No
 * orchestration property (`gate`, `retry`, `onFailure`, `briefTemplate`…) is
 * accepted — orchestration lives in an external Coday agent, not in the file.
 */
class WorkflowDefinitionValidatorTest {

    private fun step(
        id: String,
        kind: String = "agent",
        name: String = "Agent",
        dependsOn: List<String> = emptyList(),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id,
        "name" to "Step $id",
        "responsibility" to linkedMapOf<String, Any?>("kind" to kind, "name" to name),
        "dependsOn" to dependsOn,
    ) + extra

    private fun definition(vararg steps: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to "1",
        "workflowType" to "demo",
        "version" to "1.0.0",
        "title" to "Demo workflow",
        "steps" to steps.toList(),
    )

    private fun errorCode(input: Any?): String? =
        (WorkflowDefinitionValidator.validate(input) as? WorkflowDefinitionValidation.Invalid)?.error?.code

    private fun errorPath(input: Any?): String? =
        (WorkflowDefinitionValidator.validate(input) as? WorkflowDefinitionValidation.Invalid)?.error?.path

    @Test
    fun `accepts the minimal declarative format with mixed agent code and human steps`() {
        val result = WorkflowDefinitionValidator.validate(
            definition(
                step("design", kind = "agent", name = "architect"),
                step("build", kind = "code", name = "forge-frontend-verification", dependsOn = listOf("design")),
                step("review", kind = "human", name = "reviewer", dependsOn = listOf("build")),
            ),
        )

        assertThat(result).isInstanceOf(WorkflowDefinitionValidation.Valid::class.java)
        val steps = (result as WorkflowDefinitionValidation.Valid).steps
        assertThat(steps.map { it.id }).containsExactly("design", "build", "review")
        assertThat(steps.map { it.responsibility.kind })
            .containsExactly(ResponsibilityKind.AGENT, ResponsibilityKind.CODE, ResponsibilityKind.HUMAN)
        assertThat(steps[1].responsibility.name).isEqualTo("forge-frontend-verification")
        assertThat(steps[1].dependsOn).containsExactly("design")
    }

    @Test
    fun `normalizes a step to exactly id name responsibility dependsOn`() {
        val result = WorkflowDefinitionValidator.validate(definition(step("one")))
        val normalized = (result as WorkflowDefinitionValidation.Valid).definition
        @Suppress("UNCHECKED_CAST")
        val normalizedStep = (normalized["steps"] as List<Map<String, Any?>>).first()
        assertThat(normalizedStep.keys).containsExactly("id", "name", "responsibility", "dependsOn")
        @Suppress("UNCHECKED_CAST")
        val responsibility = normalizedStep["responsibility"] as Map<String, Any?>
        assertThat(responsibility.keys).containsExactly("kind", "name")
    }

    @Test
    fun `rejects an orchestration gate field on a step`() {
        assertThat(errorCode(definition(step("one", extra = mapOf("gate" to "manual")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
        assertThat(errorPath(definition(step("one", extra = mapOf("gate" to "manual"))))).isEqualTo("steps[0]")
    }

    @Test
    fun `rejects retry onFailure and briefTemplate orchestration fields`() {
        assertThat(errorCode(definition(step("one", extra = mapOf("retry" to 3)))))
            .isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
        assertThat(errorCode(definition(step("one", extra = mapOf("onFailure" to "fail")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
        assertThat(errorCode(definition(step("one", extra = mapOf("briefTemplate" to "x")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
    }

    @Test
    fun `rejects an unknown field inside responsibility`() {
        val bad = linkedMapOf<String, Any?>(
            "id" to "one",
            "name" to "Step one",
            "responsibility" to linkedMapOf<String, Any?>("kind" to "agent", "name" to "dev", "retry" to 1),
            "dependsOn" to emptyList<String>(),
        )
        assertThat(errorCode(definition(bad))).isEqualTo(WorkflowDefinitionErrorCodes.INVALID_RESPONSIBILITY)
    }

    @Test
    fun `rejects an invalid responsibility kind`() {
        assertThat(errorCode(definition(step("one", kind = "machine"))))
            .isEqualTo(WorkflowDefinitionErrorCodes.INVALID_RESPONSIBILITY)
    }

    @Test
    fun `requires a responsibility name`() {
        val bad = linkedMapOf<String, Any?>(
            "id" to "one",
            "name" to "Step one",
            "responsibility" to linkedMapOf<String, Any?>("kind" to "code"),
            "dependsOn" to emptyList<String>(),
        )
        assertThat(errorCode(definition(bad))).isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
        assertThat(errorPath(definition(bad))).isEqualTo("steps[0].responsibility.name")
    }

    @Test
    fun `rejects duplicate step ids`() {
        assertThat(errorCode(definition(step("one"), step("one"))))
            .isEqualTo(WorkflowDefinitionErrorCodes.DUPLICATE_STEP_ID)
    }

    @Test
    fun `rejects a dependency on an unknown step`() {
        assertThat(errorCode(definition(step("one", dependsOn = listOf("missing")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.MISSING_DEPENDENCY)
    }

    @Test
    fun `rejects a self dependency`() {
        assertThat(errorCode(definition(step("one", dependsOn = listOf("one")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.SELF_DEPENDENCY)
    }

    @Test
    fun `rejects a cycle in the DAG`() {
        assertThat(errorCode(definition(step("one", dependsOn = listOf("two")), step("two", dependsOn = listOf("one")))))
            .isEqualTo(WorkflowDefinitionErrorCodes.DEPENDENCY_CYCLE)
    }

    @Test
    fun `rejects an unknown top-level field`() {
        val bad = definition(step("one")) + mapOf("orchestration" to "smart")
        assertThat(errorCode(bad)).isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
        assertThat(errorPath(bad)).isEqualTo("$")
    }
}

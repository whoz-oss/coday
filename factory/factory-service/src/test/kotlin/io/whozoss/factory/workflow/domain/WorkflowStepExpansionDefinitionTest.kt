package io.whozoss.factory.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Definition-level rules of the optional step field `expansion`. */
class WorkflowStepExpansionDefinitionTest {

    private fun step(
        id: String,
        kind: String = "agent",
        dependsOn: List<String> = emptyList(),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id,
        "name" to "Step $id",
        "responsibility" to linkedMapOf<String, Any?>("kind" to kind, "name" to "Archay"),
        "dependsOn" to dependsOn,
    ) + extra

    private val expansion = mapOf("expansion" to mapOf("mode" to "append"))

    private fun definition(vararg steps: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to "1",
        "workflowType" to "demo",
        "version" to "1.0.0",
        "title" to "Demo workflow",
        "steps" to steps.toList(),
    )

    private fun error(input: Any?): WorkflowDefinitionError? =
        (WorkflowDefinitionValidator.validate(input) as? WorkflowDefinitionValidation.Invalid)?.error

    @Test
    fun `an agent leaf step may declare expansion and it is kept in the normalized definition`() {
        val result = WorkflowDefinitionValidator.validate(definition(step("plan", extra = expansion)))

        val valid = result as WorkflowDefinitionValidation.Valid
        assertThat(valid.steps.single().expansion).isEqualTo(WorkflowStepExpansion("append"))
        @Suppress("UNCHECKED_CAST")
        val normalized = (valid.definition["steps"] as List<Map<String, Any?>>).single()
        assertThat(normalized["expansion"]).isEqualTo(mapOf("mode" to "append"))
    }

    @Test
    fun `a definition without expansion keeps its historical normalized keys and hash`() {
        val plain = definition(step("plan"))
        val valid = WorkflowDefinitionValidator.validate(plain) as WorkflowDefinitionValidation.Valid

        @Suppress("UNCHECKED_CAST")
        val normalized = (valid.definition["steps"] as List<Map<String, Any?>>).single()
        assertThat(normalized.keys).containsExactly("id", "name", "responsibility", "dependsOn")

        val withExpansion = WorkflowDefinitionValidator.validate(definition(step("plan", extra = expansion)))
            as WorkflowDefinitionValidation.Valid
        assertThat(hashWorkflowDefinition(withExpansion.definition)).isNotEqualTo(hashWorkflowDefinition(valid.definition))
    }

    @Test
    fun `only the append mode with no other key is accepted`() {
        listOf(
            mapOf("mode" to "replace"),
            mapOf("mode" to "append", "extra" to 1),
            mapOf<String, Any?>(),
            "append",
        ).forEach { value ->
            val result = error(definition(step("plan", extra = mapOf("expansion" to value))))
            assertThat(result?.code).isEqualTo(WorkflowDefinitionErrorCodes.INVALID_VALUE)
            assertThat(result?.path).isEqualTo("steps[0].expansion")
        }
    }

    @Test
    fun `expansion is rejected on a human or a code step`() {
        listOf("human", "code").forEach { kind ->
            val result = error(definition(step("plan", kind = kind, extra = expansion)))
            assertThat(result?.path).isEqualTo("steps[0].expansion")
            assertThat(result?.details?.get("reason")).isEqualTo("expansion_not_agent")
        }
    }

    @Test
    fun `expansion is rejected on a step that has dependents`() {
        val result = error(definition(step("plan", extra = expansion), step("next", dependsOn = listOf("plan"))))

        assertThat(result?.details?.get("reason")).isEqualTo("expansion_not_leaf")
    }

    @Test
    fun `expansion is rejected on a decision step`() {
        val decision = mapOf(
            "decision" to mapOf(
                "mode" to "multiple",
                "routes" to listOf(mapOf("name" to "front", "target" to "a"), mapOf("name" to "back", "target" to "b")),
            ),
        )
        val result = error(
            definition(
                step("plan", extra = expansion + decision),
                step("a", dependsOn = listOf("plan")),
                step("b", dependsOn = listOf("plan")),
                step("join", dependsOn = listOf("a", "b"), extra = mapOf("join" to "plan")),
            ),
        )

        assertThat(result?.details?.get("reason")).isEqualTo("expansion_with_decision")
    }

    @Test
    fun `two steps declaring expansion are rejected`() {
        val result = error(definition(step("one", extra = expansion), step("two", extra = expansion)))

        assertThat(result?.details?.get("reason")).isEqualTo("multiple_expanders")
        assertThat(result?.path).isEqualTo("steps[1].expansion")
    }
}

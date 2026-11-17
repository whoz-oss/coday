package io.whozoss.factory.workflow.domain

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.workflow.service.toPolicyDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExplicitStepContractTest {
    private fun fixture(): Map<String, Any?> = ObjectMapper().readValue(
        requireNotNull(javaClass.getResourceAsStream("/workflows/git-checkpoint-contract.json")),
        object : TypeReference<Map<String, Any?>>() {},
    )

    private fun withContract(contract: Map<String, Any?>): Map<String, Any?> {
        val original = fixture()
        val step = mapOf(
            "id" to "one", "name" to "One", "responsibility" to mapOf("kind" to "agent", "name" to "Dev"),
            "dependsOn" to emptyList<String>(),
        )
        return original + ("steps" to listOf(step + contract))
    }

    @Test
    fun `fixture preserves ordered obligations through validation policy and projection`() {
        val raw = fixture()
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        assertThat(valid.definition).isEqualTo(raw)
        val record = WorkflowDefinitionRecord("illustrative-git-checkpoint", "1.0.0", hashWorkflowDefinition(raw), valid.definition)
        assertThat(record.toPolicyDefinition().steps).isEqualTo(valid.steps)
        val created = createWorkflowInstance(
            WorkflowStartCommand("wf-contract", record.workflowType, "Checkpoint"),
            WorkflowDefinitionInput(record.workflowType, record.version, record.definitionHash, valid.steps),
            ControllerExecutionInput("test", "agentos", "runner"),
        )
        val projection = WorkflowProjectionValidator.validate(created.projection, "wf-contract") as ProjectionValidation.Valid
        val projectedSteps = projection.normalized["steps"] as List<*>
        valid.steps.forEachIndexed { index, step ->
            assertThat(WorkflowStepContract.fromJson(projectedSteps[index] as Map<*, *>)).isEqualTo(step.contract)
        }
        assertThat(valid.steps[1].dependsOn).containsExactly("initialize-branch")
        assertThat(valid.steps).allMatch { it.responsibility.kind == ResponsibilityKind.AGENT }
    }

    @Test
    fun `partial null blank wrong-type and oversized contracts are rejected`() {
        val good = mapOf("entry" to emptyList<String>(), "mission" to "Do the requested work", "exit" to listOf("Report"))
        val invalid = listOf(
            mapOf("mission" to "Only mission"), good + ("mission" to null), good + ("mission" to " "),
            good + ("mission" to "x".repeat(16_001)), good + ("entry" to "sync"),
            good + ("exit" to listOf(12)), good + ("exit" to listOf(" ")),
            good + ("entry" to List(51) { "sync" }), good + ("exit" to listOf("x".repeat(4_001))),
        )
        invalid.forEach { contract ->
            assertThat(WorkflowDefinitionValidator.validate(withContract(contract)))
                .describedAs(contract.toString()).isInstanceOf(WorkflowDefinitionValidation.Invalid::class.java)
        }
        assertThat(WorkflowDefinitionValidator.validate(withContract(good)))
            .isInstanceOf(WorkflowDefinitionValidation.Valid::class.java)
    }

    @Test
    fun `projection rejects partial or malformed contract instead of silently dropping it`() {
        fun projection(contract: Map<String, Any?>): Map<String, Any?> = mapOf(
            "schemaVersion" to "2", "workflowId" to "wf-contract", "workflowType" to "demo",
            "title" to "Demo", "status" to "ready", "steps" to listOf(mapOf(
                "id" to "one", "name" to "One", "status" to "ready",
                "responsibility" to mapOf("kind" to "agent", "name" to "Dev"),
            ) + contract),
        )
        listOf(
            mapOf("mission" to "Analyze"),
            mapOf("entry" to emptyList<String>(), "mission" to " ", "exit" to emptyList<String>()),
            mapOf("entry" to listOf(4), "mission" to "Analyze", "exit" to emptyList<String>()),
        ).forEach { contract ->
            assertThat(WorkflowProjectionValidator.validate(projection(contract), "wf-contract"))
                .isInstanceOf(ProjectionValidation.Invalid::class.java)
        }
    }

    @Test
    fun `legacy definition stays byte-shape compatible with no injected contract or hash change`() {
        val raw = withContract(emptyMap())
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        assertThat(valid.steps.single().contract).isNull()
        assertThat(valid.definition).isEqualTo(raw)
        assertThat(hashWorkflowDefinition(valid.definition)).isEqualTo(hashWorkflowDefinition(raw))
        assertThat(WorkflowStepContract.fromJson((valid.definition["steps"] as List<*>).single() as Map<*, *>)).isNull()
    }
}

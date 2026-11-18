package io.whozoss.factory.workflow.domain

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ForgeDeliveryDraftDefinitionTest {
    @Test
    fun `delivery draft orders every design discipline and packet gate after ProductEngineer readiness`() {
        val path = Path.of("..", "docs", "WZ-36147-delivery-workflow.draft.json")
        val raw = Files.newInputStream(path).use {
            ObjectMapper().readValue(it, object : TypeReference<Map<String, Any?>>() {})
        }
        val validated = WorkflowDefinitionValidator.validate(raw)
        assertThat(validated).isInstanceOf(WorkflowDefinitionValidation.Valid::class.java)
        assertThat(executionPolicyOf((validated as WorkflowDefinitionValidation.Valid).definition)
            ?.humanCheckpointPolicyRequired).isTrue()
        val steps = validated.steps
        assertThat(steps.map { it.id }).containsExactly(
            "delivery-preflight",
            "design-assessment",
            "product-design",
            "ux-design",
            "technical-design",
            "implementation-planning",
            "qa-design",
            "packet-assembly",
            "packet-approval",
            "implementation-build",
            "implementation-verification",
            "implementation-review",
            "integration-observation",
            "technical-validation",
            "functional-validation",
            "delivery-completion",
        )
        assertThat(steps.first().responsibility.name).isEqualTo("ProductEngineer")
        assertThat(steps.first().dependsOn).isEmpty()
        steps.drop(1).filter { it.id !in setOf("integration-observation", "technical-validation", "functional-validation", "delivery-completion") }.forEach { step ->
            val index = steps.indexOf(step)
            assertThat(step.dependsOn).describedAs("dependencies of ${step.id}")
                .containsExactly(steps[index - 1].id)
        }
        assertThat(steps.filter { it.id !in setOf("packet-approval", "technical-validation", "functional-validation") })
            .allMatch { it.responsibility.kind == ResponsibilityKind.AGENT }
        assertThat(steps.filter { it.responsibility.kind == ResponsibilityKind.HUMAN }.map { it.id })
            .containsExactly("packet-approval", "technical-validation", "functional-validation")
        assertThat(steps.single { it.id == "implementation-build" }.responsibility.name).isEqualTo("ForgeDeveloper")
        assertThat(steps.single { it.id == "implementation-verification" }.responsibility.name).isEqualTo("ForgeVerifier")
        assertThat(steps.single { it.id == "implementation-review" }.responsibility.name).isEqualTo("ForgeReviewer")
        assertThat(steps.single { it.id == "integration-observation" }.responsibility.name).isEqualTo("ForgeDeploymentObserver")
        assertThat(steps.single { it.id == "integration-observation" }.dependsOn).containsExactly("implementation-review")
        assertThat(steps.single { it.id == "technical-validation" }.dependsOn).containsExactly("implementation-review")
        assertThat(steps.single { it.id == "functional-validation" }.dependsOn).containsExactly("integration-observation")
        assertThat(steps.last().responsibility.name).isEqualTo("ForgeCloser")
        assertThat(steps.last().dependsOn).containsExactly("technical-validation", "functional-validation")
        assertThat(steps).allMatch { step ->
            step.contract != null && step.contract.entry.isNotEmpty() &&
                step.contract.mission.isNotBlank() && step.contract.exit.isNotEmpty()
        }
    }
}

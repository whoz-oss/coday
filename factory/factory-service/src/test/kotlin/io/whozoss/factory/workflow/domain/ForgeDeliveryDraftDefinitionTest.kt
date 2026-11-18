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
        val steps = (validated as WorkflowDefinitionValidation.Valid).steps
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
        )
        assertThat(steps.first().responsibility.name).isEqualTo("ProductEngineer")
        assertThat(steps.first().dependsOn).isEmpty()
        steps.drop(1).forEachIndexed { index, step ->
            assertThat(step.dependsOn).describedAs("dependencies of ${step.id}")
                .containsExactly(steps[index].id)
        }
        assertThat(steps.dropLast(1)).allMatch { it.responsibility.kind == ResponsibilityKind.AGENT }
        assertThat(steps.last().responsibility.kind).isEqualTo(ResponsibilityKind.HUMAN)
        assertThat(steps.last().dependsOn).containsExactly("packet-assembly")
        assertThat(steps).allMatch { step ->
            step.contract != null && step.contract.entry.isNotEmpty() &&
                step.contract.mission.isNotBlank() && step.contract.exit.isNotEmpty()
        }
    }
}

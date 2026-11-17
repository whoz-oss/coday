package io.whozoss.factory.workflow.domain

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ForgeDeliveryDraftDefinitionTest {
    @Test
    fun `delivery draft is a valid configurable definition with ProductEngineer first`() {
        val path = Path.of("..", "docs", "WZ-36147-delivery-workflow.draft.json")
        val raw = Files.newInputStream(path).use {
            ObjectMapper().readValue(it, object : TypeReference<Map<String, Any?>>() {})
        }
        val validated = WorkflowDefinitionValidator.validate(raw)
        assertThat(validated).isInstanceOf(WorkflowDefinitionValidation.Valid::class.java)
        val steps = (validated as WorkflowDefinitionValidation.Valid).steps
        assertThat(steps).hasSize(1)
        assertThat(steps.single().id).isEqualTo("delivery-preflight")
        assertThat(steps.single().responsibility.name).isEqualTo("ProductEngineer")
        assertThat(steps.single().dependsOn).isEmpty()
    }
}

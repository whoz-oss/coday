package io.whozoss.factory.forge.bmad

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ForgeDeliveryDraftDefinitionTest {
    @Test
    fun deliveryDraftUsesStandardReviewerAndJoinsBothHumanDecisions() {
        val path = Path.of("..", "docs", "WZ-36147-delivery-workflow.draft.json")
        val raw = Files.newInputStream(path).use {
            ObjectMapper().readValue(it, object : TypeReference<Map<String, Any?>>() {})
        }
        @Suppress("UNCHECKED_CAST")
        val steps = raw["steps"] as List<Map<String, Any?>>
        fun step(id: String) = steps.single { it["id"] == id }
        fun agent(id: String) = (step(id)["responsibility"] as Map<*, *>)["name"]

        assertThat(agent("delivery-preflight")).isEqualTo("ProductEngineer")
        assertThat(agent("implementation-build")).isEqualTo("ForgeDeveloper")
        assertThat(agent("implementation-review")).isEqualTo("Reviewer")
        assertThat(step("technical-validation")["dependsOn"] as List<*>)
            .containsExactly("implementation-review")
        assertThat(step("functional-validation")["dependsOn"] as List<*>)
            .containsExactly("implementation-review")
        assertThat(steps.takeLast(2).map { it["id"] })
            .containsExactly("technical-validation", "functional-validation")
        assertThat(steps.none { it["id"] == "delivery-completion" }).isTrue()
        assertThat(steps.none { it.containsKey("join") }).isTrue()
        assertThat(steps.none { it["id"] == "integration-observation" }).isTrue()
    }
}

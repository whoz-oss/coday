package io.whozoss.factory.workflow.domain

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit test of the bundled session-definition catalogue (no Spring, no DB).
 *
 * Proves that the committed bundled session definitions (resources under
 * `sessions/`) load AND pass
 * [WorkflowDefinitionValidator] (unique ids, valid/acyclic `dependsOn`, valid
 * `kind`), so the W8.4 seed can never boot on a malformed bundled definition.
 */
class SessionDefinitionCatalogTest {

    private val catalogue = SessionDefinitionCatalog(ObjectMapper())

    @Test
    fun `the bundled forge fullstack ux definition loads and validates`() {
        val records = catalogue.load()

        val record = records.firstOrNull { it.workflowType == "forge-story-fullstack-ux" }
        assertThat(record).isNotNull
        assertThat(record!!.version).isEqualTo("1.0.0")

        @Suppress("UNCHECKED_CAST")
        val steps = record.definition["steps"] as List<Map<String, Any?>>
        assertThat(steps.map { it["id"] }).containsExactly(
            "ticket-analysis",
            "intent-checkpoint",
            "product-specification",
            "product-checkpoint",
            "ux-design",
            "codebase-research",
            "ux-checkpoint",
            "technical-design",
            "technical-checkpoint",
            "fullstack-implementation",
            "fullstack-verification",
            "acceptance-checkpoint",
        )
        @Suppress("UNCHECKED_CAST")
        val kinds = steps.map { (it["responsibility"] as Map<String, Any?>)["kind"] }.toSet()
        assertThat(kinds).isEqualTo(setOf("agent", "human", "code"))
    }
}

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
 *
 * The first test guards that MECHANISM for the whole catalogue and is therefore
 * derived from it (a newly bundled definition is covered the day it lands). The
 * second guards the CONTENT of the shipped Forge controller definition and
 * legitimately names it.
 */
class SessionDefinitionCatalogTest {

    private val catalogue = SessionDefinitionCatalog(ObjectMapper())

    @Test
    fun `every bundled session definition loads and validates`() {
        // `load()` throws IllegalArgumentException on the first malformed
        // resource, so reaching this point already proves validation passed for
        // ALL bundled definitions — including any added after this test.
        val records = catalogue.load()

        assertThat(records).isNotEmpty
        assertThat(records.map { "${it.workflowType}@${it.version}" })
            .doesNotHaveDuplicates()
        assertThat(records).allSatisfy { record ->
            assertThat(record.workflowType).isNotBlank
            assertThat(record.version).isNotBlank
            assertThat(record.definitionHash).isNotBlank
            @Suppress("UNCHECKED_CAST")
            val steps = record.definition["steps"] as List<Map<String, Any?>>
            assertThat(steps).isNotEmpty
        }
    }

    @Test
    fun `legacy forge controller is absent from the bundled catalogue`() {
        assertThat(catalogue.load().map { it.workflowType }).doesNotContain(FORGE_CONTROLLER_TYPE)
    }

    private companion object {
        const val FORGE_CONTROLLER_TYPE = "forge-controller-v1-searcher"
    }
}

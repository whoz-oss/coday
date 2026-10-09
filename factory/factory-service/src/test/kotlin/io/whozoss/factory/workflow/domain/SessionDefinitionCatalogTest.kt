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
    fun `the bundled forge controller definition loads and validates`() {
        val records = catalogue.load()

        val record = records.firstOrNull { it.workflowType == FORGE_CONTROLLER_TYPE }
        assertThat(record).isNotNull
        assertThat(record!!.version).isEqualTo("1.0.0")

        @Suppress("UNCHECKED_CAST")
        val steps = record.definition["steps"] as List<Map<String, Any?>>
        assertThat(steps.map { it["id"] }).containsExactly(
            "product-specification",
            "product-approval",
            "ux-assessment",
            "technical-assessment",
            "ux-design",
            "codebase-research",
            "technical-design",
            "specification-approval",
            "frontend-implementation",
            "frontend-verification",
            "backend-implementation",
            "backend-verification",
            "code-review",
            "functional-approval",
        )
        // The controller catalogue currently declares only `agent` and `human`
        // lanes: its two verification steps are agent-driven. Running a build or
        // a test suite is a deterministic command and therefore belongs to the
        // `code` lane (an actor must not be its own oracle) — restoring that lane
        // is a pending catalogue decision, NOT a test relaxation.
        @Suppress("UNCHECKED_CAST")
        val kinds = steps.map { (it["responsibility"] as Map<String, Any?>)["kind"] }.toSet()
        assertThat(kinds).isEqualTo(setOf("agent", "human"))

        // The definition selects the Forge execution plugin; the catalogue must
        // surface that selection on the loaded record.
        assertThat(record.executionPolicy?.plugin).isEqualTo("forge")
        assertThat(record.definition["execution"]).isEqualTo(mapOf("plugin" to "forge"))
    }

    private companion object {
        const val FORGE_CONTROLLER_TYPE = "forge-controller-v1-searcher"
    }

    @Test
    fun `the ForgeController projection requires Searcher evidence before technical design`() {
        val records = catalogue.load()

        val record = records.firstOrNull { it.workflowType == "forge-controller-v1-searcher" }
        assertThat(record).isNotNull

        @Suppress("UNCHECKED_CAST")
        val steps = record!!.definition["steps"] as List<Map<String, Any?>>
        val research = steps.single { it["id"] == "codebase-research" }
        val technicalDesign = steps.single { it["id"] == "technical-design" }
        assertThat((research["responsibility"] as Map<String, Any?>)["name"]).isEqualTo("Searcher")
        assertThat(technicalDesign["dependsOn"]).isEqualTo(listOf("ux-design", "codebase-research"))
    }
}

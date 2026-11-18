package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.persistence.OrganizationScope
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import io.whozoss.factory.workflow.service.WorkflowDefinitionSeeder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Characterizes the current same-key shadowing path; this is not an acceptance test for CE09. */
class WorkflowDefinitionSeederShadowingTest {
    private val scope = OrganizationScope("org-shadow")
    private val repository = mockk<WorkflowRepository>()
    private val catalog = mockk<SessionDefinitionCatalog>()
    private val seeder = WorkflowDefinitionSeeder(repository, catalog)

    private fun definition(title: String): WorkflowDefinitionRecord {
        val body = mapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "forge.delivery",
            "version" to "1.0.0",
            "title" to title,
            "steps" to listOf(mapOf(
                "id" to "gate",
                "name" to "Gate",
                "responsibility" to mapOf("kind" to "human", "name" to "reviewer"),
                "dependsOn" to emptyList<String>(),
            )),
        )
        return WorkflowDefinitionRecord("forge.delivery", "1.0.0", hashWorkflowDefinition(body), body)
    }

    @Test
    fun `preexisting same-key tenant definition shadows a later plugin definition`() {
        val tenant = definition("Tenant copy")
        val plugin = definition("Official plugin release")
        assertThat(tenant.definitionHash).isNotEqualTo(plugin.definitionHash)
        every { repository.listDefinitions(scope) } returns listOf(tenant)
        every { catalog.load() } returns listOf(plugin)

        assertThat(seeder.seed(scope)).isEmpty()
        verify(exactly = 0) { repository.saveDefinition(any<OrganizationScope>(), any()) }
    }

    @Test
    fun `matching bytes still have no publication provenance`() {
        val tenant = definition("Same bytes")
        val plugin = definition("Same bytes")
        every { repository.listDefinitions(scope) } returns listOf(tenant)
        every { catalog.load() } returns listOf(plugin)

        assertThat(seeder.seed(scope)).isEmpty()
        verify(exactly = 0) { repository.saveDefinition(any<OrganizationScope>(), any()) }
    }
}

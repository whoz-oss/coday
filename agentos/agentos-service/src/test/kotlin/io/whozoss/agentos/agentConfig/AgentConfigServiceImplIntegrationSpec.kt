package io.whozoss.agentos.agentConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.scheduledPrompt.Planning
import io.whozoss.agentos.scheduledPrompt.Recurrence
import io.whozoss.agentos.scheduledPrompt.ScheduledPrompt
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptRepository
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Integration tests for [AgentConfigServiceImpl] against an embedded Neo4j instance.
 *
 * Covers the cascade soft-delete behaviour triggered by [AgentConfigService.delete]
 * and [AgentConfigService.disable], verifying that the Cypher batch queries in
 * [io.whozoss.agentos.scheduledPrompt.ScheduledPromptNodeNeo4jRepository] and
 * [io.whozoss.agentos.prompt.PromptNodeNeo4jRepository] behave correctly in all
 * edge cases (normal path, orphaned SP, disable/re-enable).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AgentConfigServiceImplIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var agentConfigService: AgentConfigService
    @Autowired lateinit var promptRepository: PromptRepository
    @Autowired lateinit var scheduledPromptRepository: ScheduledPromptRepository

    private val namespaceId = UUID.randomUUID()

    private fun agent(name: String = "agent-${UUID.randomUUID()}", enabled: Boolean = true) =
        agentConfigService.create(
            AgentConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                name = name,
                enabled = enabled,
            ),
        )

    private fun prompt(agentConfigId: UUID? = null, name: String = "prompt-${UUID.randomUUID()}") =
        promptRepository.save(
            Prompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                agentConfigId = agentConfigId,
                name = name,
                content = listOf("Content"),
            ),
        )

    private fun scheduledPrompt(agentId: UUID, promptTemplateId: UUID, name: String = "sp-${UUID.randomUUID()}") =
        scheduledPromptRepository.save(
            ScheduledPrompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                agentConfigId = agentId,
                promptTemplateId = promptTemplateId,
                name = name,
                recurrence = Recurrence(
                    unit = SchedulerUnit.WEEK,
                    days = listOf(DayOfWeek.MONDAY),
                    timeUtc = LocalTime.of(8, 0),
                ),
                planning = Planning(
                    startDate = LocalDate.of(2026, 1, 1),
                    endType = SchedulerEndType.NEVER,
                ),
                enabled = true,
                nextRunAt = Instant.parse("2026-01-05T08:00:00Z"),
            ),
        )

    init {

        // -------------------------------------------------------------------------
        // delete — cascade to Prompts (agentConfigId)
        // -------------------------------------------------------------------------

        "delete cascades soft-delete to linked Prompts" {
            val a = agent()
            val p1 = prompt(agentConfigId = a.id)
            val p2 = prompt(agentConfigId = a.id)

            promptRepository.findByIds(listOf(p1.id, p2.id), withRemoved = false) shouldHaveSize 2

            agentConfigService.delete(a.id).shouldBeTrue()

            promptRepository.findByIds(listOf(p1.id, p2.id), withRemoved = false).shouldBeEmpty()
            promptRepository.findByIds(listOf(p1.id, p2.id), withRemoved = true) shouldHaveSize 2
        }

        // -------------------------------------------------------------------------
        // delete — cascade to ScheduledPrompts + their linked Prompts
        // -------------------------------------------------------------------------

        "delete cascades soft-delete to ScheduledPrompts and their linked Prompts" {
            val a = agent()
            val template = prompt()
            val sp = scheduledPrompt(agentId = a.id, promptTemplateId = template.id)

            scheduledPromptRepository.findByIds(listOf(sp.id), withRemoved = false) shouldHaveSize 1
            promptRepository.findByIds(listOf(template.id), withRemoved = false) shouldHaveSize 1

            agentConfigService.delete(a.id).shouldBeTrue()

            scheduledPromptRepository.findByIds(listOf(sp.id), withRemoved = false).shouldBeEmpty()
            scheduledPromptRepository.findByIds(listOf(sp.id), withRemoved = true) shouldHaveSize 1
            promptRepository.findByIds(listOf(template.id), withRemoved = false).shouldBeEmpty()
            promptRepository.findByIds(listOf(template.id), withRemoved = true) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // delete — orphaned SP (linked Prompt already removed)
        // -------------------------------------------------------------------------

        "delete soft-deletes ScheduledPrompt even when its linked Prompt is already removed" {
            // Regression: the old MATCH...MATCH query silently skipped the SP when the
            // Prompt was already soft-deleted (inner-join semantics). The fixed query
            // uses OPTIONAL MATCH so the SP is always soft-deleted regardless.
            val a = agent()
            val template = prompt()
            val sp = scheduledPrompt(agentId = a.id, promptTemplateId = template.id)

            promptRepository.delete(template.id)
            promptRepository.findByIds(listOf(template.id), withRemoved = false).shouldBeEmpty()

            agentConfigService.delete(a.id).shouldBeTrue()

            scheduledPromptRepository.findByIds(listOf(sp.id), withRemoved = false).shouldBeEmpty()
            scheduledPromptRepository.findByIds(listOf(sp.id), withRemoved = true) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // disable / enable — cascade to ScheduledPrompts
        // -------------------------------------------------------------------------

        "disable cascades to ScheduledPrompts; re-enabling agent does NOT re-enable schedulers" {
            val a = agent(enabled = true)
            val template = prompt()
            val sp = scheduledPrompt(agentId = a.id, promptTemplateId = template.id)

            agentConfigService.disable(a.id)
            scheduledPromptRepository.findByIds(listOf(sp.id), withRemoved = false).single().enabled shouldBe false

            agentConfigService.enable(a.id)
            scheduledPromptRepository.findByIds(listOf(sp.id), withRemoved = false).single().enabled shouldBe false
        }
    }
}

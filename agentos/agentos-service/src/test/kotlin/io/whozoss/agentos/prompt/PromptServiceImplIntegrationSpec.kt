package io.whozoss.agentos.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
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
 * Integration tests for [PromptService.delete] guard against deletion of a Prompt
 * referenced by an active ScheduledPrompt, against an embedded Neo4j instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class PromptServiceImplIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var promptService: PromptService
    @Autowired lateinit var promptRepository: PromptRepository
    @Autowired lateinit var scheduledPromptRepository: ScheduledPromptRepository

    private val namespaceId = UUID.randomUUID()
    private val agentConfigId = UUID.randomUUID()

    private fun prompt(name: String = "prompt-${UUID.randomUUID()}") =
        promptRepository.save(
            Prompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                name = name,
                content = listOf("Content"),
            ),
        )

    private fun scheduledPrompt(
        promptTemplateId: UUID,
        name: String = "sp-${UUID.randomUUID()}",
    ) = scheduledPromptRepository.save(
        ScheduledPrompt(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            agentConfigId = agentConfigId,
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

        "delete Prompt referenced by active ScheduledPrompt throws ConflictException" {
            val p = prompt()
            scheduledPrompt(promptTemplateId = p.id)

            shouldThrow<ConflictException> { promptService.delete(p.id) }
        }

        "delete Prompt referenced only by removed ScheduledPrompt succeeds" {
            val p = prompt()
            val sp = scheduledPrompt(promptTemplateId = p.id)
            scheduledPromptRepository.delete(sp.metadata.id)

            promptService.delete(p.id).shouldBeTrue()
            promptService.findById(p.id).shouldBeNull()
        }
    }
}

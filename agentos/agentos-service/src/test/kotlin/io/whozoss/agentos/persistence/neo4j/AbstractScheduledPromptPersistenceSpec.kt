package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

/**
 * Persistence contract tests for [ScheduledPromptRepository] custom Cypher queries.
 *
 * Covers the delta-sync invariant: raw-Cypher mutation methods that bypass `repository.save()`
 * must bump `sp.modified` so that a `modifiedSince` cursor correctly surfaces the changes.
 *
 * Specifically tested:
 * - [ScheduledPromptRepository.softDeleteWithPromptsByAgentConfigId]: tombstoned prompts are
 *   visible via `findByScope(withRemoved = true, modifiedSince = cursor)` after the cascade delete.
 * - [ScheduledPromptRepository.disableByAgentConfigId]: disabled prompts surface via
 *   `findByScope(modifiedSince = cursor)`.
 */
abstract class AbstractScheduledPromptPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var scheduledPromptRepo: ScheduledPromptRepository

    @Autowired lateinit var namespaceRepo: NamespaceRepository

    @Autowired lateinit var driver: Driver

    // ---------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------

    private fun namespace() =
        Namespace(metadata = EntityMetadata(), name = "ns-${UUID.randomUUID()}", externalId = "ext-${UUID.randomUUID()}")

    /**
     * Inserts a minimal ScheduledPrompt node directly via Cypher, bypassing the service-layer
     * validations (AgentConfig existence check, Prompt creation, etc.) that are not relevant
     * to the persistence contract under test.
     *
     * [agentConfigId] is stored as a property on the node; no graph edge is required for
     * [softDeleteWithPromptsByAgentConfigId] and [disableByAgentConfigId] to match it.
     */
    private fun insertScheduledPromptNode(namespaceId: UUID, agentConfigId: UUID): UUID {
        val id = UUID.randomUUID()
        driver.session().use { session ->
            session.run(
                $$"""
                CREATE (sp:ScheduledPrompt {
                    id:              $id,
                    namespaceId:     $namespaceId,
                    agentConfigId:   $agentConfigId,
                    promptTemplateId: randomUUID(),
                    name:            'test-prompt-' + $id,
                    tripleKey:       $namespaceId + ':_:test-prompt-' + $id,
                    unit:            'WEEK',
                    days:            [],
                    timeUtc:         localtime('09:00:00'),
                    startDate:       date('2025-01-01'),
                    endType:         'NEVER',
                    enabled:         true,
                    nextRunAt:       datetime('2025-01-06T09:00:00Z'),
                    version:         0,
                    created:         datetime('2025-01-01T00:00:00Z'),
                    modified:        datetime('2025-01-01T00:00:00Z')
                })
                """,
                mapOf(
                    "id" to id.toString(),
                    "namespaceId" to namespaceId.toString(),
                    "agentConfigId" to agentConfigId.toString(),
                ),
            )
        }
        return id
    }

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // -------------------------------------------------------------------------
        // softDeleteWithPromptsByAgentConfigId — delta-sync invariant
        // -------------------------------------------------------------------------

        "softDeleteWithPromptsByAgentConfigId bumps modified so tombstone is visible via modifiedSince cursor" {
            val ns = namespaceRepo.save(namespace())
            val agentConfigId = UUID.randomUUID()
            insertScheduledPromptNode(ns.id, agentConfigId)

            // Cursor recorded after the prompt was created (its modified is 2025-01-01).
            // Any timestamp after the node's stored modified qualifies.
            val cursor = Instant.parse("2025-01-02T00:00:00Z")

            scheduledPromptRepo.softDeleteWithPromptsByAgentConfigId(agentConfigId)

            val results = scheduledPromptRepo.findByScope(
                namespaceId = ns.id,
                userId = null,
                agentConfigIds = null,
                withRemoved = true,
                modifiedSince = cursor,
            )

            results shouldHaveSize 1
            results.first().metadata.removed shouldBe true
        }

        "softDeleteWithPromptsByAgentConfigId tombstone is invisible without withRemoved=true" {
            val ns = namespaceRepo.save(namespace())
            val agentConfigId = UUID.randomUUID()
            insertScheduledPromptNode(ns.id, agentConfigId)

            scheduledPromptRepo.softDeleteWithPromptsByAgentConfigId(agentConfigId)

            scheduledPromptRepo.findByScope(
                namespaceId = ns.id,
                userId = null,
                agentConfigIds = null,
                withRemoved = false,
                modifiedSince = null,
            ).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // disableByAgentConfigId — delta-sync invariant
        // -------------------------------------------------------------------------

        "disableByAgentConfigId bumps modified so disabled prompt is visible via modifiedSince cursor" {
            val ns = namespaceRepo.save(namespace())
            val agentConfigId = UUID.randomUUID()
            insertScheduledPromptNode(ns.id, agentConfigId)

            val cursor = Instant.parse("2025-01-02T00:00:00Z")

            scheduledPromptRepo.disableByAgentConfigId(agentConfigId)

            val results = scheduledPromptRepo.findByScope(
                namespaceId = ns.id,
                userId = null,
                agentConfigIds = null,
                withRemoved = false,
                modifiedSince = cursor,
            )

            results shouldHaveSize 1
            results.first().enabled shouldBe false
        }

        // -------------------------------------------------------------------------
        // findByScope — modifiedSince basic filtering
        // -------------------------------------------------------------------------

        "findByScope with modifiedSince excludes prompts modified before the cursor" {
            val ns = namespaceRepo.save(namespace())
            insertScheduledPromptNode(ns.id, UUID.randomUUID())

            // Cursor is after the node's stored modified (2025-01-01) — nothing should match.
            val cursor = Instant.parse("2026-01-01T00:00:00Z")

            scheduledPromptRepo.findByScope(
                namespaceId = ns.id,
                userId = null,
                agentConfigIds = null,
                withRemoved = false,
                modifiedSince = cursor,
            ).shouldBeEmpty()
        }

        "findByScope with modifiedSince=null returns all active prompts in scope" {
            val ns = namespaceRepo.save(namespace())
            insertScheduledPromptNode(ns.id, UUID.randomUUID())

            val results = scheduledPromptRepo.findByScope(
                namespaceId = ns.id,
                userId = null,
                agentConfigIds = null,
                withRemoved = false,
                modifiedSince = null,
            )

            results shouldHaveSize 1
        }
    }
}

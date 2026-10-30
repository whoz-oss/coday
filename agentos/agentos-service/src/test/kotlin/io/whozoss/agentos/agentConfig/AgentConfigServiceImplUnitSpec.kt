package io.whozoss.agentos.agentConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.scheduledPrompt.InMemoryScheduledPromptRepository
import io.whozoss.agentos.scheduledPrompt.Planning
import io.whozoss.agentos.scheduledPrompt.Recurrence
import io.whozoss.agentos.scheduledPrompt.ScheduledPrompt
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AgentConfigServiceImplUnitSpec :
    StringSpec({

        fun repository(): AgentConfigRepository {
            val inMemory =
                InMemoryEntityRepository<AgentConfig, UUID?>(
                    parentIdExtractor = { it.namespaceId },
                    comparator = compareBy { it.name },
                )
            return object :
                AgentConfigRepository,
                EntityRepository<AgentConfig, UUID?> by inMemory {
                // findAvailableByNamespaceIdAndUserId is a Neo4j-only query; not exercised in unit tests.
                override fun findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId: UUID?,
                    userId: UUID?,
                    agentName: String?,
                    withDisabled: Boolean,
                ): List<AgentConfig> = throw UnsupportedOperationException("Not available in InMemoryEntityRepository")

                // ConcurrentHashMap does not support null keys, so platform agents (namespaceId=null)
                // are retrieved by scanning all stored entities rather than calling findByParent(null).
                private fun platformAgents(): List<AgentConfig> = inMemory.findAll().filter { it.namespaceId == null }

                override fun findByParent(parentId: UUID?): List<AgentConfig> =
                    if (parentId == null) platformAgents() else inMemory.findByParent(parentId)

                override fun findByParent(
                    parentId: UUID?,
                    withDisabled: Boolean,
                ): List<AgentConfig> {
                    val all = findByParent(parentId)
                    return if (withDisabled) all else all.filter { it.enabled }
                }
            }
        }

        val userService = mockk<UserService>(relaxed = true)
        val promptRepository = mockk<PromptRepository>(relaxed = true)

        fun service(
            repo: AgentConfigRepository = repository(),
            pr: PromptRepository = promptRepository,
            spr: InMemoryScheduledPromptRepository = InMemoryScheduledPromptRepository(),
            us: UserService = userService,
        ) = AgentConfigServiceImpl(repo, pr, spr, us)

        val namespaceId: UUID = UUID.randomUUID()

        fun config(
            name: String,
            nsId: UUID? = namespaceId,
            modelName: String? = "BIG",
        ) = AgentConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = nsId,
            name = name,
            modelName = modelName,
        )

        // -------------------------------------------------------------------------
        // findByName
        // -------------------------------------------------------------------------

        "findByName returns config with exact name match" {
            val repo = repository()
            val svc = service(repo)
            val saved = repo.save(config("Dev", nsId = namespaceId))

            svc.findByName(namespaceId, "Dev") shouldBe saved
        }

        "findByName is case-insensitive" {
            val repo = repository()
            val svc = service(repo)
            val saved = repo.save(config("Dev", nsId = namespaceId))

            svc.findByName(namespaceId, "dev") shouldBe saved
            svc.findByName(namespaceId, "DEV") shouldBe saved
        }

        "findByName returns null when no config matches" {
            val svc = service()

            svc.findByName(namespaceId, "unknown").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // create — uniqueness check
        // -------------------------------------------------------------------------

        "create throws when an AgentConfig with the same name already exists in the namespace" {
            val repo = repository()
            val svc = service(repo)
            repo.save(config("Dev", nsId = namespaceId))

            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                svc.create(config("Dev", nsId = namespaceId))
            }
        }

        "create uniqueness check is case-insensitive" {
            val repo = repository()
            val svc = service(repo)
            repo.save(config("Dev", nsId = namespaceId))

            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                svc.create(config("dev", nsId = namespaceId))
            }
        }

        "create allows same name in a different namespace" {
            val repo = repository()
            val svc = service(repo)
            val otherNs = UUID.randomUUID()
            repo.save(config("Dev", nsId = namespaceId))

            svc.create(config("Dev", nsId = otherNs)).name shouldBe "Dev"
        }

        "create allows same name at platform level independently of namespace" {
            val repo = repository()
            val svc = service(repo)
            repo.save(config("Dev", nsId = namespaceId))

            svc.create(config("Dev", nsId = null)).name shouldBe "Dev"
        }

        "create succeeds when no name conflict exists" {
            val repo = repository()
            val svc = service(repo)

            svc.create(config("Dev", nsId = namespaceId)).name shouldBe "Dev"
        }

        // -------------------------------------------------------------------------
        // update — uniqueness check
        // -------------------------------------------------------------------------

        "update throws when renaming to a name already taken in the same namespace" {
            val repo = repository()
            val svc = service(repo)
            repo.save(config("Alpha", nsId = namespaceId))
            val beta = repo.save(config("Beta", nsId = namespaceId))

            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                svc.update(beta.copy(name = "Alpha"))
            }
        }

        "update allows saving an entity with its own current name (no self-conflict)" {
            val repo = repository()
            val svc = service(repo)
            val saved = repo.save(config("Dev", nsId = namespaceId))

            svc.update(saved.copy(instructions = "updated")).name shouldBe "Dev"
        }

        "update uniqueness check is case-insensitive" {
            val repo = repository()
            val svc = service(repo)
            repo.save(config("Alpha", nsId = namespaceId))
            val beta = repo.save(config("Beta", nsId = namespaceId))

            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                svc.update(beta.copy(name = "alpha"))
            }
        }

        // -------------------------------------------------------------------------
        // findAvailableByUserExternalId
        // -------------------------------------------------------------------------

        "findAvailableByUserExternalId throws ResourceNotFoundException when user is not found" {
            val us = mockk<UserService> { every { findByExternalId("ghost@example.com") } returns null }
            val svc = service(us = us)

            shouldThrow<ResourceNotFoundException> {
                svc.findAvailableByUserExternalId(namespaceId, "ghost@example.com")
            }
        }

        "findByName is scoped to the given namespace" {
            val repo = repository()
            val svc = service(repo)
            val otherNamespaceId = UUID.randomUUID()
            repo.save(config("Dev", nsId = otherNamespaceId))

            svc.findByName(namespaceId, "Dev").shouldBeNull()
        }

        "findByName falls back to platform agents when not found in namespace" {
            val repo = repository()
            val svc = service(repo)
            val platform = repo.save(config("Coday", nsId = null))

            svc.findByName(namespaceId, "Coday") shouldBe platform
        }

        "findByName platform fallback is case-insensitive" {
            val repo = repository()
            val svc = service(repo)
            val platform = repo.save(config("Coday", nsId = null))

            svc.findByName(namespaceId, "coday") shouldBe platform
            svc.findByName(namespaceId, "CODAY") shouldBe platform
        }

        "findByName namespace agent takes priority over platform agent with same name" {
            val repo = repository()
            val svc = service(repo)
            val nsAgent = repo.save(config("Coday", nsId = namespaceId))
            repo.save(config("Coday", nsId = null))

            svc.findByName(namespaceId, "Coday") shouldBe nsAgent
        }

        "findByName with null namespaceId returns platform agent directly" {
            val repo = repository()
            val svc = service(repo)
            val platform = repo.save(config("Coday", nsId = null))

            svc.findByName(null, "Coday") shouldBe platform
        }

        // -------------------------------------------------------------------------
        // findByNamespace
        // -------------------------------------------------------------------------

        "findByNamespace with withDisabled=true returns all configs" {
            val repo = repository()
            val svc = service(repo)
            repo.save(config("Published").copy(enabled = true))
            repo.save(config("Unpublished").copy(enabled = false))

            val result = svc.findByNamespace(namespaceId, withDisabled = true)
            result.map { it.name }.toSet() shouldBe setOf("Published", "Unpublished")
        }

        "findByNamespace with withDisabled=false returns only enabled configs" {
            val repo = repository()
            val svc = service(repo)
            repo.save(config("Published").copy(enabled = true))
            repo.save(config("Unpublished").copy(enabled = false))

            val result = svc.findByNamespace(namespaceId, withDisabled = false)
            result.map { it.name } shouldBe listOf("Published")
        }

        "findByNamespace defaults to withDisabled=true" {
            val repo = repository()
            val svc = service(repo)
            repo.save(config("Alpha").copy(enabled = false))
            repo.save(config("Beta").copy(enabled = true))

            val result = svc.findByNamespace(namespaceId)
            result shouldHaveSize 2
        }

        // -------------------------------------------------------------------------
        // delete — scheduler cascade
        // -------------------------------------------------------------------------

        fun scheduledPrompt(agentConfigId: UUID, name: String, enabled: Boolean = true) = ScheduledPrompt(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            agentConfigId = agentConfigId,
            promptTemplateId = UUID.randomUUID(),
            name = name,
            enabled = enabled,
            recurrence = Recurrence(
                unit = SchedulerUnit.WEEK,
                days = listOf(DayOfWeek.MONDAY),
                timeUtc = LocalTime.of(9, 0),
            ),
            planning = Planning(
                startDate = LocalDate.of(2025, 1, 1),
                endType = SchedulerEndType.NEVER,
            ),
            nextRunAt = Instant.parse("2025-01-06T09:00:00Z"),
        )

        "delete soft-deletes all scheduled prompts referencing the deleted agent" {
            val repo = repository()
            val scheduledPromptRepo = InMemoryScheduledPromptRepository()
            val svc = service(repo, spr = scheduledPromptRepo)

            val agent = repo.save(config("Dev", nsId = namespaceId))
            val agentId = agent.metadata.id

            scheduledPromptRepo.save(scheduledPrompt(agentId, "sp1", enabled = true))
            scheduledPromptRepo.save(scheduledPrompt(agentId, "sp2", enabled = true))
            scheduledPromptRepo.save(scheduledPrompt(agentId, "sp3", enabled = false))

            svc.delete(agentId) shouldBe true

            // All schedulers should be soft-deleted (no longer visible in active queries)
            scheduledPromptRepo.findByScope(null, null, listOf(agentId)) shouldHaveSize 0
        }

        "disable disables all enabled scheduled prompts referencing the disabled agent" {
            val repo = repository()
            val scheduledPromptRepo = InMemoryScheduledPromptRepository()
            val svc = service(repo, spr = scheduledPromptRepo)

            val agent = repo.save(config("Dev", nsId = namespaceId))
            val agentId = agent.metadata.id

            scheduledPromptRepo.save(scheduledPrompt(agentId, "sp1", enabled = true))
            scheduledPromptRepo.save(scheduledPrompt(agentId, "sp2", enabled = false))

            svc.disable(agentId)

            scheduledPromptRepo.findByScope(null, null, listOf(agentId)).forEach { sp ->
                sp.enabled shouldBe false
            }
        }

        "disable does not affect scheduled prompts of other agents" {
            val repo = repository()
            val scheduledPromptRepo = InMemoryScheduledPromptRepository()
            val svc = service(repo, spr = scheduledPromptRepo)

            val agent = repo.save(config("Dev", nsId = namespaceId))
            val otherAgentId = UUID.randomUUID()

            scheduledPromptRepo.save(scheduledPrompt(otherAgentId, "other-sp", enabled = true))

            svc.disable(agent.metadata.id)

            scheduledPromptRepo.findByScope(null, null, listOf(otherAgentId)).single().enabled shouldBe true
        }

        "delete does not affect scheduled prompts of other agents" {
            val repo = repository()
            val scheduledPromptRepo = InMemoryScheduledPromptRepository()
            val svc = service(repo, spr = scheduledPromptRepo)

            val agent = repo.save(config("Dev", nsId = namespaceId))
            val otherAgentId = UUID.randomUUID()

            scheduledPromptRepo.save(scheduledPrompt(otherAgentId, "other-sp", enabled = true))

            svc.delete(agent.metadata.id) shouldBe true

            // The other agent's scheduler must remain enabled
            scheduledPromptRepo.findByScope(null, null, listOf(otherAgentId)).single().enabled shouldBe true
        }
    })

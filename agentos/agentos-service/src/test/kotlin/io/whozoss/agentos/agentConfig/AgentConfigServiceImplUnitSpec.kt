package io.whozoss.agentos.agentConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import java.util.UUID

class AgentConfigServiceImplUnitSpec : StringSpec({

    fun repository(): AgentConfigRepository {
        val inMemory = InMemoryEntityRepository<AgentConfig, UUID>(
            parentIdExtractor = { it.namespaceId },
            comparator = compareBy { it.name },
        )
        return object : AgentConfigRepository,
            io.whozoss.agentos.entity.EntityRepository<AgentConfig, UUID> by inMemory {
            // findAvailableByNamespaceIdAndUserId is a Neo4j-only query; not exercised in unit tests.
            override fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String?): List<AgentConfig> =
                throw UnsupportedOperationException("Not available in InMemoryEntityRepository")

            // Returns configs from the in-memory store, filtered by withDisabled.
            override fun findByParent(parentId: UUID, withDisabled: Boolean): List<AgentConfig> =
                if (withDisabled) inMemory.findByParent(parentId)
                else inMemory.findByParent(parentId).filter { it.enabled }
        }
    }

    val userService = mockk<UserService>(relaxed = true)

    fun service(repo: AgentConfigRepository = repository(), us: UserService = userService) = AgentConfigServiceImpl(repo, us)

    val namespaceId: UUID = UUID.randomUUID()

    fun config(
        name: String,
        nsId: UUID = namespaceId,
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

})

package io.whozoss.agentos.agentConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

class AgentConfigServiceImplUnitSpec : StringSpec({

    fun repository(): AgentConfigRepository =
        object :
            AgentConfigRepository,
            io.whozoss.agentos.entity.EntityRepository<AgentConfig, UUID>
            by InMemoryEntityRepository(
                parentIdExtractor = { it.namespaceId },
                comparator = compareBy { it.name },
            ) {
            // findAvailableByNamespaceIdAndUserId is a Neo4j-only query; not exercised in unit tests.
            override fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String?): List<AgentConfig> =
                throw UnsupportedOperationException("Not available in InMemoryEntityRepository")
        }

    val userService = mockk<UserService>(relaxed = true)

    fun service(repo: AgentConfigRepository = repository(), us: UserService = userService) = AgentConfigServiceImpl(repo, us)

    val namespaceId: UUID = UUID.randomUUID()

    fun adminUser(id: UUID = UUID.randomUUID()) = User(
        metadata = EntityMetadata(id = id),
        externalId = "admin@example.com",
        email = "admin@example.com",
        isAdmin = true,
    )

    fun regularUser(id: UUID = UUID.randomUUID()) = User(
        metadata = EntityMetadata(id = id),
        externalId = "user@example.com",
        email = "user@example.com",
        isAdmin = false,
    )

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

    "findAvailableByUserExternalId bypasses DEPLOYED_TO for admin user and returns all namespace agents" {
        val repo = repository()
        val admin = adminUser()
        val us = mockk<UserService> { every { findByExternalId(admin.externalId) } returns admin }
        val svc = service(repo, us)
        repo.save(config("Agent-A", nsId = namespaceId))
        repo.save(config("Agent-B", nsId = namespaceId))

        val result = svc.findAvailableByUserExternalId(namespaceId, admin.externalId)

        result.map { it.name } shouldContainExactlyInAnyOrder listOf("Agent-A", "Agent-B")
        // findAvailableByNamespaceIdAndUserId (which throws) must NOT have been called
    }

    "findAvailableByUserExternalId calls Neo4j query for regular user" {
        val repo = object : AgentConfigRepository,
            io.whozoss.agentos.entity.EntityRepository<AgentConfig, UUID>
            by InMemoryEntityRepository(
                parentIdExtractor = { it.namespaceId },
                comparator = compareBy { it.name },
            ) {
            var callCount = 0
            override fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String?): List<AgentConfig> {
                callCount++
                return emptyList()
            }
        }
        val regular = regularUser()
        val us = mockk<UserService> { every { findByExternalId(regular.externalId) } returns regular }
        val svc = service(repo, us)

        svc.findAvailableByUserExternalId(namespaceId, regular.externalId)

        repo.callCount shouldBe 1
    }

    // -------------------------------------------------------------------------
    // findAvailableByNamespaceIdAndUserId — admin bypass
    // -------------------------------------------------------------------------

    "findAvailableByNamespaceIdAndUserId bypasses DEPLOYED_TO for admin user and returns all namespace agents" {
        val repo = repository()
        val admin = adminUser()
        val us = mockk<UserService> { every { findById(admin.id) } returns admin }
        val svc = service(repo, us)
        repo.save(config("Agent-A", nsId = namespaceId))
        repo.save(config("Agent-B", nsId = namespaceId))

        val result = svc.findAvailableByNamespaceIdAndUserId(namespaceId, admin.id, null)

        result.map { it.name } shouldContainExactlyInAnyOrder listOf("Agent-A", "Agent-B")
    }

    "findAvailableByNamespaceIdAndUserId filters by agentName for admin user" {
        val repo = repository()
        val admin = adminUser()
        val us = mockk<UserService> { every { findById(admin.id) } returns admin }
        val svc = service(repo, us)
        repo.save(config("Agent-A", nsId = namespaceId))
        repo.save(config("Agent-B", nsId = namespaceId))

        val result = svc.findAvailableByNamespaceIdAndUserId(namespaceId, admin.id, "Agent-A")

        result shouldHaveSize 1
        result.first().name shouldBe "Agent-A"
    }

    "findAvailableByNamespaceIdAndUserId agentName filter is case-insensitive for admin user" {
        val repo = repository()
        val admin = adminUser()
        val us = mockk<UserService> { every { findById(admin.id) } returns admin }
        val svc = service(repo, us)
        repo.save(config("My-Agent", nsId = namespaceId))

        val result = svc.findAvailableByNamespaceIdAndUserId(namespaceId, admin.id, "MY-AGENT")

        result shouldHaveSize 1
        result.first().name shouldBe "My-Agent"
    }

    "findAvailableByNamespaceIdAndUserId returns empty for admin when agentName does not match" {
        val repo = repository()
        val admin = adminUser()
        val us = mockk<UserService> { every { findById(admin.id) } returns admin }
        val svc = service(repo, us)
        repo.save(config("Agent-A", nsId = namespaceId))

        val result = svc.findAvailableByNamespaceIdAndUserId(namespaceId, admin.id, "nonexistent")

        result.shouldBeEmpty()
    }

    "findAvailableByNamespaceIdAndUserId calls Neo4j query for regular user" {
        val repo = object : AgentConfigRepository,
            io.whozoss.agentos.entity.EntityRepository<AgentConfig, UUID>
            by InMemoryEntityRepository(
                parentIdExtractor = { it.namespaceId },
                comparator = compareBy { it.name },
            ) {
            var callCount = 0
            override fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String?): List<AgentConfig> {
                callCount++
                return emptyList()
            }
        }
        val regular = regularUser()
        val us = mockk<UserService> { every { findById(regular.id) } returns regular }
        val svc = service(repo, us)

        svc.findAvailableByNamespaceIdAndUserId(namespaceId, regular.id, null)

        repo.callCount shouldBe 1
    }

    "findAvailableByNamespaceIdAndUserId calls Neo4j query when user is not found (unknown userId)" {
        val repo = object : AgentConfigRepository,
            io.whozoss.agentos.entity.EntityRepository<AgentConfig, UUID>
            by InMemoryEntityRepository(
                parentIdExtractor = { it.namespaceId },
                comparator = compareBy { it.name },
            ) {
            var callCount = 0
            override fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String?): List<AgentConfig> {
                callCount++
                return emptyList()
            }
        }
        val unknownId = UUID.randomUUID()
        val us = mockk<UserService> { every { findById(unknownId) } returns null }
        val svc = service(repo, us)

        svc.findAvailableByNamespaceIdAndUserId(namespaceId, unknownId, null)

        repo.callCount shouldBe 1
    }

    "findByName is scoped to the given namespace" {
        val repo = repository()
        val svc = service(repo)
        val otherNamespaceId = UUID.randomUUID()
        repo.save(config("Dev", nsId = otherNamespaceId))

        svc.findByName(namespaceId, "Dev").shouldBeNull()
    }

})

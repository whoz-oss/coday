package io.whozoss.agentos.agentConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.time.Instant
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

    val userId = UUID.randomUUID()
    val currentUser = User(
        metadata = EntityMetadata(id = userId),
        externalId = "user@example.com",
        email = "user@example.com",
    )
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
    // create — audit field population
    // -------------------------------------------------------------------------

    "create sets createdBy and modifiedBy on the returned entity" {
        every { userService.getCurrentUser() } returns currentUser
        val svc = service()
        val entity = config("my-agent")

        val result = svc.create(entity)

        result.metadata.createdBy shouldBe userId.toString()
        result.metadata.modifiedBy shouldBe userId.toString()
    }

    "create sets created and modified timestamps on the returned entity" {
        every { userService.getCurrentUser() } returns currentUser
        val svc = service()
        val before = Instant.now()

        val result = svc.create(config("timestamped-agent"))

        val after = Instant.now()
        result.metadata.created shouldNotBe null
        result.metadata.modified shouldNotBe null
        (result.metadata.created >= before && result.metadata.created <= after) shouldBe true
        (result.metadata.modified >= before && result.metadata.modified <= after) shouldBe true
    }

    // -------------------------------------------------------------------------
    // update — audit field population
    // -------------------------------------------------------------------------

    "update sets modifiedBy from current user and preserves createdBy on the returned entity" {
        val originalCreatorId = UUID.randomUUID().toString()
        every { userService.getCurrentUser() } returns currentUser
        val repo = repository()
        val svc = service(repo)
        val existing = repo.save(
            config("existing-agent").copy(
                metadata = EntityMetadata(
                    id = UUID.randomUUID(),
                    createdBy = originalCreatorId,
                    modifiedBy = originalCreatorId,
                ),
            )
        )

        val result = svc.update(existing)

        result.metadata.createdBy shouldBe originalCreatorId
        result.metadata.modifiedBy shouldBe userId.toString()
    }

    "update sets modified timestamp on the returned entity" {
        every { userService.getCurrentUser() } returns currentUser
        val repo = repository()
        val svc = service(repo)
        val existing = repo.save(config("existing-agent"))
        val before = Instant.now()

        val result = svc.update(existing)

        result.metadata.modified shouldNotBe null
        (result.metadata.modified.toEpochMilli() >= before.toEpochMilli()) shouldBe true
    }

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

    // -------------------------------------------------------------------------
    // create / update — verify audit enrichment via mocked repository
    // -------------------------------------------------------------------------

    "create stamps createdBy and modifiedBy from current user" {
        val newUserId = UUID.randomUUID()
        val user = User(
            metadata = EntityMetadata(id = newUserId),
            externalId = "test@example.com",
            email = "test@example.com",
        )
        val mockRepo = mockk<AgentConfigRepository>()
        every { userService.getCurrentUser() } returns user
        every { mockRepo.save(any()) } answers { firstArg() }

        val entity = AgentConfig(
            metadata = EntityMetadata(),
            namespaceId = UUID.randomUUID(),
            name = "test-agent",
        )
        service(mockRepo).create(entity)

        verify {
            mockRepo.save(
                withArg {
                    it.metadata.createdBy shouldBe newUserId.toString()
                    it.metadata.modifiedBy shouldBe newUserId.toString()
                }
            )
        }
    }

    "create stamps created and modified timestamps" {
        val before = Instant.now()
        val newUserId = UUID.randomUUID()
        val mockRepo = mockk<AgentConfigRepository>()
        every { userService.getCurrentUser() } returns User(
            metadata = EntityMetadata(id = newUserId),
            externalId = "test@example.com",
            email = "test@example.com",
        )
        every { mockRepo.save(any()) } answers { firstArg() }

        service(mockRepo).create(AgentConfig(
            metadata = EntityMetadata(),
            namespaceId = UUID.randomUUID(),
            name = "test-agent",
        ))

        val after = Instant.now()
        verify {
            mockRepo.save(
                withArg {
                    (it.metadata.created >= before && it.metadata.created <= after) shouldBe true
                    (it.metadata.modified >= before && it.metadata.modified <= after) shouldBe true
                }
            )
        }
    }

    "update stamps modifiedBy from current user and preserves createdBy" {
        val creatorId = UUID.randomUUID().toString()
        val updaterId = UUID.randomUUID()
        val mockRepo = mockk<AgentConfigRepository>()
        every { userService.getCurrentUser() } returns User(
            metadata = EntityMetadata(id = updaterId),
            externalId = "updater@example.com",
            email = "updater@example.com",
        )
        every { mockRepo.save(any()) } answers { firstArg() }

        val entity = AgentConfig(
            metadata = EntityMetadata(
                createdBy = creatorId,
                modifiedBy = creatorId,
            ),
            namespaceId = UUID.randomUUID(),
            name = "test-agent",
        )
        service(mockRepo).update(entity)

        verify {
            mockRepo.save(
                withArg {
                    it.metadata.createdBy shouldBe creatorId
                    it.metadata.modifiedBy shouldBe updaterId.toString()
                }
            )
        }
    }

    "update stamps modified timestamp without changing created" {
        val originalCreated = Instant.parse("2024-01-01T00:00:00Z")
        val newUserId = UUID.randomUUID()
        val mockRepo = mockk<AgentConfigRepository>()
        every { userService.getCurrentUser() } returns User(
            metadata = EntityMetadata(id = newUserId),
            externalId = "test@example.com",
            email = "test@example.com",
        )
        every { mockRepo.save(any()) } answers { firstArg() }

        val before = Instant.now()
        service(mockRepo).update(AgentConfig(
            metadata = EntityMetadata(
                created = originalCreated,
            ),
            namespaceId = UUID.randomUUID(),
            name = "test-agent",
        ))
        val after = Instant.now()

        verify {
            mockRepo.save(
                withArg {
                    it.metadata.created shouldBe originalCreated
                    (it.metadata.modified >= before && it.metadata.modified <= after) shouldBe true
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // findByName
    // -------------------------------------------------------------------------

    "findByName is scoped to the given namespace" {
        val repo = repository()
        val svc = service(repo)
        val otherNamespaceId = UUID.randomUUID()
        repo.save(config("Dev", nsId = otherNamespaceId))

        svc.findByName(namespaceId, "Dev").shouldBeNull()
    }

})

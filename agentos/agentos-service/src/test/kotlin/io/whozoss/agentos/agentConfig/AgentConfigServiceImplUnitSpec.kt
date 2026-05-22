package io.whozoss.agentos.agentConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
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

    fun service(repo: AgentConfigRepository = repository()) = AgentConfigServiceImpl(repo, userService)

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

    "findByName is scoped to the given namespace" {
        val repo = repository()
        val svc = service(repo)
        val otherNamespaceId = UUID.randomUUID()
        repo.save(config("Dev", nsId = otherNamespaceId))

        svc.findByName(namespaceId, "Dev").shouldBeNull()
    }

})

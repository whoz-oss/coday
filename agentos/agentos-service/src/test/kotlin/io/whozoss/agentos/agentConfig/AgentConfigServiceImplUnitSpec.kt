package io.whozoss.agentos.agentConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
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
            ) {}

    fun service(repo: AgentConfigRepository = repository()) = AgentConfigServiceImpl(repo)

    val namespaceId: UUID = UUID.randomUUID()

    fun config(
        name: String,
        nsId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        modelName: String? = "BIG",
        createdAt: Instant = Instant.now(),
    ) = AgentConfig(
        metadata = EntityMetadata(id = UUID.randomUUID(), created = createdAt),
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

    // -------------------------------------------------------------------------
    // findDefault
    // -------------------------------------------------------------------------

    "findDefault returns built-in fallback when namespace has no configs" {
        val svc = service()

        val result = svc.findDefault(namespaceId)

        result shouldBe AgentConfigServiceImpl.DEFAULT_AGENT_CONFIG
    }

    "findDefault returns the oldest config when multiple exist" {
        val repo = repository()
        val svc = service(repo)
        val older = repo.save(config("Alpha", nsId = namespaceId, createdAt = Instant.ofEpochSecond(1000)))
        repo.save(config("Beta", nsId = namespaceId, createdAt = Instant.ofEpochSecond(2000)))

        svc.findDefault(namespaceId) shouldBe older
    }

    "findDefault returns the single config when only one exists" {
        val repo = repository()
        val svc = service(repo)
        val saved = repo.save(config("Solo", nsId = namespaceId))

        svc.findDefault(namespaceId).shouldNotBeNull() shouldBe saved
    }

    "findDefault is scoped to the given namespace" {
        val repo = repository()
        val svc = service(repo)
        val otherNamespaceId = UUID.randomUUID()
        repo.save(config("Alpha", nsId = otherNamespaceId))

        // own namespace is empty -> fallback
        svc.findDefault(namespaceId) shouldBe AgentConfigServiceImpl.DEFAULT_AGENT_CONFIG
    }
})

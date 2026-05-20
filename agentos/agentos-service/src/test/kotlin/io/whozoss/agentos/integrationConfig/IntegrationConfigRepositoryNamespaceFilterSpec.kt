package io.whozoss.agentos.integrationConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for the `userId IS NULL` filter on [IntegrationConfigRepository.findByParent] /
 * [IntegrationConfigRepository.findByNamespaceId]. Story 6.4 AC12, AC13 (in-memory impl).
 *
 * The same semantics must hold for the Neo4j implementation — verified by the Cypher change
 * in [IntegrationConfigNodeNeo4jRepository.findActiveByNamespaceId].
 */
class IntegrationConfigRepositoryNamespaceFilterSpec : StringSpec({

    fun repo() = InMemoryIntegrationConfigRepository()

    fun config(ns: UUID?, uid: UUID?, name: String) =
        IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = ns,
            userId = uid,
            name = name,
            integrationType = "JIRA",
        )

    val nsId = UUID.randomUUID()
    val userId = UUID.randomUUID()

    "findByParent returns only namespace-shared configs (userId IS NULL)" {
        val r = repo()
        val shared = config(nsId, null, "jira")
        val userScoped = config(nsId, userId, "github")
        r.save(shared)
        r.save(userScoped)

        val result = r.findByParent(nsId)

        result shouldHaveSize 1
        result.first().name shouldBe "jira"
    }

    "findByNamespaceId returns only namespace-shared configs (userId IS NULL)" {
        val r = repo()
        val shared = config(nsId, null, "jira")
        val userScoped = config(nsId, userId, "github")
        r.save(shared)
        r.save(userScoped)

        val result = r.findByNamespaceId(nsId)

        result shouldHaveSize 1
        result.first().name shouldBe "jira"
    }

    "findByParent returns empty when namespace has only user-scoped configs" {
        val r = repo()
        r.save(config(nsId, userId, "github"))

        r.findByParent(nsId) shouldHaveSize 0
    }

    "findByUserId still returns user-scoped configs regardless of namespace" {
        val r = repo()
        val shared = config(nsId, null, "jira")
        val userGlobal = config(null, userId, "slack")
        val userNs = config(nsId, userId, "github")
        r.save(shared)
        r.save(userGlobal)
        r.save(userNs)

        val result = r.findByUserId(userId)

        result shouldHaveSize 2
        result.map { it.name }.toSet() shouldBe setOf("slack", "github")
    }

    "findByTriple is unaffected by the namespace filter change" {
        val r = repo()
        val shared = config(nsId, null, "jira")
        val userNs = config(nsId, userId, "jira")
        r.save(shared)
        r.save(userNs)

        r.findByTriple(nsId, null, "jira")?.name shouldBe "jira"
        r.findByTriple(nsId, userId, "jira")?.name shouldBe "jira"
    }
})

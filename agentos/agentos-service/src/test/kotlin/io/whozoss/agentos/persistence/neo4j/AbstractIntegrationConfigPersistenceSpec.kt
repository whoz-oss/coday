package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Shared integration-config persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (Testcontainers or embedded)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 *
 * A [Namespace] node must exist before [IntegrationConfig] nodes are saved because
 * [io.whozoss.agentos.integrationConfig.IntegrationConfigNodeNeo4jRepository.findActiveByNamespaceId] traverses the
 * BELONGS_TO edge to the Namespace node. [namespaceRepo] is used to pre-create namespaces.
 */
abstract class AbstractIntegrationConfigPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: IntegrationConfigRepository

    @Autowired
    lateinit var namespaceRepo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    fun namespace() = Namespace(metadata = EntityMetadata(), name = "test-ns")

    fun config(
        namespaceId: UUID,
        name: String = "JIRA",
        integrationType: String = "JIRA",
        description: String? = null,
        parametersJson: String? = null,
    ) = config(
        namespaceId = namespaceId as UUID?,
        userId = null,
        name = name,
        integrationType = integrationType,
        description = description,
        parametersJson = parametersJson,
    )

    fun config(
        namespaceId: UUID?,
        userId: UUID?,
        name: String = "JIRA",
        integrationType: String = "JIRA",
        description: String? = null,
        parametersJson: String? = null,
    ) = IntegrationConfig(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        userId = userId,
        name = name,
        integrationType = integrationType,
        description = description,
        parameters =
            parametersJson?.let {
                com.fasterxml.jackson.databind
                    .ObjectMapper()
                    .readTree(it)
            },
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same config" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(config(ns.id, name = "JIRA", integrationType = "JIRA"))
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().name shouldBe "JIRA"
            found.first().integrationType shouldBe "JIRA"
        }

        "findByParent returns configs for that namespace only" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            repo.save(config(ns1.id, name = "JIRA"))
            repo.save(config(ns1.id, name = "SLACK"))
            repo.save(config(ns2.id, name = "GITHUB"))
            repo.findByParent(ns1.id) shouldHaveSize 2
            repo.findByParent(ns2.id) shouldHaveSize 1
            repo.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns configs sorted by name" {
            val ns = namespaceRepo.save(namespace())
            repo.save(config(ns.id, name = "SLACK"))
            repo.save(config(ns.id, name = "GITHUB"))
            repo.save(config(ns.id, name = "JIRA"))
            repo.findByParent(ns.id).map { it.name } shouldBe listOf("GITHUB", "JIRA", "SLACK")
        }

        "update: save with same id replaces the node" {
            val ns = namespaceRepo.save(namespace())
            val cfg = repo.save(config(ns.id, integrationType = "JIRA"))
            repo.save(cfg.copy(integrationType = "JIRA_V2"))
            val found = repo.findByIds(listOf(cfg.id))
            found shouldHaveSize 1
            found.first().integrationType shouldBe "JIRA_V2"
        }

        "soft delete removes config from findByIds" {
            val ns = namespaceRepo.save(namespace())
            val cfg = repo.save(config(ns.id))
            repo.delete(cfg.id).shouldBeTrue()
            repo.findByIds(listOf(cfg.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val ns = namespaceRepo.save(namespace())
            val cfg = repo.save(config(ns.id))
            repo.delete(cfg.id).shouldBeTrue()
            repo.delete(cfg.id).shouldBeFalse()
        }

        "deleteByParent removes all configs in namespace without touching others" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            repo.save(config(ns1.id, name = "JIRA"))
            repo.save(config(ns1.id, name = "SLACK"))
            val survivor = repo.save(config(ns2.id, name = "GITHUB"))
            val deleted = repo.deleteByParent(ns1.id)
            deleted shouldBe 2
            repo.findByParent(ns1.id).shouldBeEmpty()
            repo.findByParent(ns2.id) shouldHaveSize 1
            repo.findByParent(ns2.id).first().id shouldBe survivor.id
        }

        "saving a config does not overwrite Namespace node properties" {
            // Regression: NamespaceNode.stub() used to write empty name/description
            // onto the existing Namespace node when the BELONGS_TO edge was saved
            // via the @Relationship field.
            val ns = namespaceRepo.save(Namespace(metadata = EntityMetadata(), name = "my-namespace", description = "important"))
            repo.save(config(ns.id, name = "JIRA"))
            repo.save(config(ns.id, name = "SLACK"))
            val found = namespaceRepo.findByIds(listOf(ns.id))
            found shouldHaveSize 1
            found.first().name shouldBe "my-namespace"
            found.first().description shouldBe "important"
        }

        "config with JSON parameters round-trips correctly" {
            val ns = namespaceRepo.save(namespace())
            val json = """{"apiUrl": "https://jira.example.com", "apiKey": "s3cr3t"}"""
            val saved = repo.save(config(ns.id, parametersJson = json))
            val found = repo.findByIds(listOf(saved.id)).first()
            found.parameters?.get("apiUrl")?.asText() shouldBe "https://jira.example.com"
            found.parameters?.get("apiKey")?.asText() shouldBe "s3cr3t"
        }

        "config with description round-trips correctly" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(config(ns.id, description = "My JIRA integration"))
            val found = repo.findByIds(listOf(saved.id)).first()
            found.description shouldBe "My JIRA integration"
        }

        "config with null description round-trips correctly" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(config(ns.id, description = null))
            val found = repo.findByIds(listOf(saved.id)).first()
            found.description shouldBe null
        }

        "update preserves description" {
            val ns = namespaceRepo.save(namespace())
            val cfg = repo.save(config(ns.id, description = "original desc"))
            repo.save(cfg.copy(description = "updated desc"))
            val found = repo.findByIds(listOf(cfg.id)).first()
            found.description shouldBe "updated desc"
        }

        "config with null parameters round-trips correctly" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(config(ns.id, parametersJson = null))
            val found = repo.findByIds(listOf(saved.id)).first()
            val params = found.parameters
            assert(params == null || params.isNull) {
                "Expected parameters to be null or NullNode, but was: $params"
            }
        }

        "findByTriple ignore les rows soft-deleted" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(config(ns.id, name = "JIRA"))
            repo.delete(saved.id).shouldBeTrue()
            repo.findByTriple(ns.id, null, "JIRA") shouldBe null
        }

        "findByUserId ignore les rows soft-deleted" {
            val userId = UUID.randomUUID()
            val saved = repo.save(config(namespaceId = null, userId = userId, name = "JIRA"))
            repo.delete(saved.id).shouldBeTrue()
            repo.findByUserId(userId).shouldBeEmpty()
        }

        "save apres soft-delete sur le meme triple succeed (le tombstone ne bloque pas la recreation)" {
            val ns = namespaceRepo.save(namespace())
            val first = repo.save(config(ns.id, name = "JIRA"))
            repo.delete(first.id).shouldBeTrue()
            val second = repo.save(config(ns.id, name = "JIRA"))
            second.id shouldNotBe first.id
            repo.findByTriple(ns.id, null, "JIRA")?.id shouldBe second.id
        }

        "findPlatform returns configs with both namespaceId and userId null" {
            // Regression test: findActivePlatform uses IS NULL predicates on both
            // namespaceId and userId — SDN does not store null properties, so the
            // node has no namespaceId/userId property at all. Neo4j treats absent
            // properties as null in IS NULL predicates, so the query must match.
            val platform = repo.save(config(namespaceId = null, userId = null, name = "PLATFORM_JIRA"))
            val ns = namespaceRepo.save(namespace())
            val nsShared = repo.save(config(namespaceId = ns.id, userId = null, name = "NS_JIRA"))
            val userId = UUID.randomUUID()
            val userOnly = repo.save(config(namespaceId = null, userId = userId, name = "USER_JIRA"))

            val result = repo.findPlatform()

            result shouldHaveSize 1
            result.first().id shouldBe platform.id
            result.first().namespaceId shouldBe null
            result.first().userId shouldBe null
            result.first().name shouldBe "PLATFORM_JIRA"
        }

        // -------------------------------------------------------------------------
        // findAllForNamespaceIdAndUserId — context-scoped multi-name lookup
        // -------------------------------------------------------------------------

        "findAllForNamespaceIdAndUserId — returns all four layers when all match" {
            // All four layers exist for the same name. The query must return all of them
            // so the caller can apply precedence logic in Kotlin.
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val platform = repo.save(config(namespaceId = null, userId = null, name = "JIRA"))
            val nsShared = repo.save(config(namespaceId = ns.id, userId = null, name = "JIRA"))
            val userGlobal = repo.save(config(namespaceId = null, userId = userId, name = "JIRA"))
            val userNs = repo.save(config(namespaceId = ns.id, userId = userId, name = "JIRA"))

            val result = repo.findAllForNamespaceIdAndUserId(ns.id, userId)

            result shouldHaveSize 4
            result.map { it.id }.toSet() shouldBe setOf(platform.id, nsShared.id, userGlobal.id, userNs.id)
        }

        "findAllForNamespaceIdAndUserId — excludes configs for a different namespace" {
            val ns1 = namespaceRepo.save(namespace())
            val ns2 = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val forNs1 = repo.save(config(namespaceId = ns1.id, userId = null, name = "JIRA"))
            val forNs2 = repo.save(config(namespaceId = ns2.id, userId = null, name = "JIRA"))
            // user×ns2 must NOT appear when querying for ns1
            repo.save(config(namespaceId = ns2.id, userId = userId, name = "JIRA"))

            val result = repo.findAllForNamespaceIdAndUserId(ns1.id, userId)

            result shouldHaveSize 1
            result.first().id shouldBe forNs1.id
        }

        "findAllForNamespaceIdAndUserId — excludes configs for a different user" {
            val ns = namespaceRepo.save(namespace())
            val user1 = UUID.randomUUID()
            val user2 = UUID.randomUUID()
            val nsShared = repo.save(config(namespaceId = ns.id, userId = null, name = "JIRA"))
            val forUser1 = repo.save(config(namespaceId = ns.id, userId = user1, name = "JIRA"))
            // user2 override must NOT appear when querying for user1
            repo.save(config(namespaceId = ns.id, userId = user2, name = "JIRA"))

            val result = repo.findAllForNamespaceIdAndUserId(ns.id, user1)

            result shouldHaveSize 2
            result.map { it.id }.toSet() shouldBe setOf(nsShared.id, forUser1.id)
        }

        "findAllForNamespaceIdAndUserId — platform config is visible without namespace" {
            // When namespaceId=null and userId=null (platform-only lookup), only the
            // platform layer is returned — namespace-shared configs are excluded.
            val ns = namespaceRepo.save(namespace())
            val platform = repo.save(config(namespaceId = null, userId = null, name = "JIRA"))
            repo.save(config(namespaceId = ns.id, userId = null, name = "JIRA"))

            val result = repo.findAllForNamespaceIdAndUserId(null, null)

            result shouldHaveSize 1
            result.first().id shouldBe platform.id
        }

        "findAllForNamespaceIdAndUserId — excludes soft-deleted configs" {
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val active = repo.save(config(namespaceId = ns.id, userId = null, name = "JIRA"))
            val deleted = repo.save(config(namespaceId = ns.id, userId = null, name = "SLACK"))
            repo.delete(deleted.id)

            val result = repo.findAllForNamespaceIdAndUserId(ns.id, userId)

            result shouldHaveSize 1
            result.first().id shouldBe active.id
        }

        "findAllForNamespaceIdAndUserId — user-global config is visible when userId matches" {
            // A user-global config (namespaceId=null, userId=U) must be returned when
            // querying for (namespaceId=N, userId=U) — it is reachable from any namespace.
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val userGlobal = repo.save(config(namespaceId = null, userId = userId, name = "GITHUB"))

            val result = repo.findAllForNamespaceIdAndUserId(ns.id, userId)

            result shouldHaveSize 1
            result.first().id shouldBe userGlobal.id
        }

        // -------------------------------------------------------------------------

        "findByTriple matchs distinctly the 4 modes (platform, ns-only, user-only, ns x user)" {
            val ns = namespaceRepo.save(namespace())
            val userId = UUID.randomUUID()
            val platform = repo.save(config(namespaceId = null, userId = null, name = "JIRA"))
            val nsOnly = repo.save(config(namespaceId = ns.id, userId = null, name = "JIRA"))
            val userOnly = repo.save(config(namespaceId = null, userId = userId, name = "JIRA"))
            val nsAndUser = repo.save(config(namespaceId = ns.id, userId = userId, name = "JIRA"))

            repo.findByTriple(null, null, "JIRA")?.id shouldBe platform.id
            repo.findByTriple(ns.id, null, "JIRA")?.id shouldBe nsOnly.id
            repo.findByTriple(null, userId, "JIRA")?.id shouldBe userOnly.id
            repo.findByTriple(ns.id, userId, "JIRA")?.id shouldBe nsAndUser.id
        }
    }
}

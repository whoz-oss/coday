package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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
        parametersJson: String? = null,
    ) = IntegrationConfig(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        name = name,
        integrationType = integrationType,
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

        "config with null parameters round-trips correctly" {
            val ns = namespaceRepo.save(namespace())
            val saved = repo.save(config(ns.id, parametersJson = null))
            val found = repo.findByIds(listOf(saved.id)).first()
            val params = found.parameters
            assert(params == null || params.isNull) {
                "Expected parameters to be null or NullNode, but was: $params"
            }
        }
    }
}

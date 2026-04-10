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
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Shared integration-config persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (Testcontainers or embedded)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 */
abstract class AbstractIntegrationConfigPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: IntegrationConfigRepository

    @Autowired
    lateinit var driver: Driver

    fun config(
        namespaceId: UUID = UUID.randomUUID(),
        name: String = "JIRA",
        integrationType: String = "JIRA",
        parametersJson: String? = null,
    ) = IntegrationConfig(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        name = name,
        integrationType = integrationType,
        parameters = parametersJson?.let {
            com.fasterxml.jackson.databind.ObjectMapper().readTree(it)
        },
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByIds returns the same config" {
            val cfg = config(name = "JIRA", integrationType = "JIRA")
            val saved = repo.save(cfg)
            val found = repo.findByIds(listOf(saved.id))
            found shouldHaveSize 1
            found.first().id shouldBe saved.id
            found.first().name shouldBe "JIRA"
            found.first().integrationType shouldBe "JIRA"
        }

        "findByParent returns configs for that namespace only" {
            val ns1 = UUID.randomUUID()
            val ns2 = UUID.randomUUID()
            repo.save(config(ns1, name = "JIRA"))
            repo.save(config(ns1, name = "SLACK"))
            repo.save(config(ns2, name = "GITHUB"))
            repo.findByParent(ns1) shouldHaveSize 2
            repo.findByParent(ns2) shouldHaveSize 1
            repo.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns configs sorted by name" {
            val ns = UUID.randomUUID()
            repo.save(config(ns, name = "SLACK"))
            repo.save(config(ns, name = "GITHUB"))
            repo.save(config(ns, name = "JIRA"))
            repo.findByParent(ns).map { it.name } shouldBe listOf("GITHUB", "JIRA", "SLACK")
        }

        "update: save with same id replaces the node" {
            val cfg = repo.save(config(integrationType = "JIRA"))
            repo.save(cfg.copy(integrationType = "JIRA_V2"))
            val found = repo.findByIds(listOf(cfg.id))
            found shouldHaveSize 1
            found.first().integrationType shouldBe "JIRA_V2"
        }

        "soft delete removes config from findByIds" {
            val cfg = repo.save(config())
            repo.delete(cfg.id).shouldBeTrue()
            repo.findByIds(listOf(cfg.id)).shouldBeEmpty()
        }

        "double delete returns false" {
            val cfg = repo.save(config())
            repo.delete(cfg.id).shouldBeTrue()
            repo.delete(cfg.id).shouldBeFalse()
        }

        "deleteByParent removes all configs in namespace without touching others" {
            val ns1 = UUID.randomUUID()
            val ns2 = UUID.randomUUID()
            repo.save(config(ns1, name = "JIRA"))
            repo.save(config(ns1, name = "SLACK"))
            val survivor = repo.save(config(ns2, name = "GITHUB"))
            val deleted = repo.deleteByParent(ns1)
            deleted shouldBe 2
            repo.findByParent(ns1).shouldBeEmpty()
            repo.findByParent(ns2) shouldHaveSize 1
            repo.findByParent(ns2).first().id shouldBe survivor.id
        }

        "config with JSON parameters round-trips correctly" {
            val ns = UUID.randomUUID()
            val json = """{"apiUrl": "https://jira.example.com", "apiKey": "s3cr3t"}"""
            val cfg = config(ns, parametersJson = json)
            val saved = repo.save(cfg)
            val found = repo.findByIds(listOf(saved.id)).first()
            found.parameters?.get("apiUrl")?.asText() shouldBe "https://jira.example.com"
            found.parameters?.get("apiKey")?.asText() shouldBe "s3cr3t"
        }

        "config with null parameters round-trips correctly" {
            val ns = UUID.randomUUID()
            val cfg = config(ns, parametersJson = null)
            val saved = repo.save(cfg)
            val found = repo.findByIds(listOf(saved.id)).first()
            val params = found.parameters
            assert(params == null || params.isNull) {
                "Expected parameters to be null or NullNode, but was: $params"
            }
        }
    }
}

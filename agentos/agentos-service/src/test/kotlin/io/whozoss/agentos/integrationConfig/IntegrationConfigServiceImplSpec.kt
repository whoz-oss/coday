package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [IntegrationConfigServiceImpl].
 *
 * Uses an [InMemoryIntegrationConfigRepository] to keep tests fast and isolated.
 * Each test builds its own service instance to guarantee full isolation.
 */
class IntegrationConfigServiceImplSpec : StringSpec() {
    private val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    private fun newService(): IntegrationConfigServiceImpl =
        IntegrationConfigServiceImpl(InMemoryIntegrationConfigRepository())

    private fun config(
        namespaceId: UUID = UUID.randomUUID(),
        name: String = "JIRA",
        integrationType: String = "JIRA",
        parametersJson: String? = null,
    ): IntegrationConfig =
        IntegrationConfig(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            name = name,
            integrationType = integrationType,
            parameters = parametersJson?.let { mapper.readTree(it) },
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "create and findById returns the same config" {
            val service = newService()
            val cfg = config()

            val saved = service.create(cfg)
            val found = service.findById(saved.metadata.id)

            found.shouldNotBeNull()
            found.metadata.id shouldBe saved.metadata.id
            found.name shouldBe "JIRA"
            found.integrationType shouldBe "JIRA"
        }

        "findById returns null for unknown id" {
            val service = newService()
            service.findById(UUID.randomUUID()).shouldBeNull()
        }

        "findByParent returns only configs for the given namespace" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "JIRA"))
            service.create(config(namespaceId = nsA, name = "SLACK"))
            service.create(config(namespaceId = nsB, name = "GITHUB"))

            service.findByParent(nsA) shouldHaveSize 2
            service.findByParent(nsB) shouldHaveSize 1
            service.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns configs sorted by name" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.create(config(namespaceId = nsId, name = "SLACK"))
            service.create(config(namespaceId = nsId, name = "GITHUB"))
            service.create(config(namespaceId = nsId, name = "JIRA"))

            val names = service.findByParent(nsId).map { it.name }
            names shouldBe listOf("GITHUB", "JIRA", "SLACK")
        }

        // -------------------------------------------------------------------------
        // findByNamespaceAndName
        // -------------------------------------------------------------------------

        "findByNamespaceAndName returns the matching config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            service.create(config(namespaceId = nsId, name = "JIRA"))

            val found = service.findByNamespaceAndName(nsId, "JIRA")
            found.shouldNotBeNull()
            found.name shouldBe "JIRA"
        }

        "findByNamespaceAndName returns null when name does not exist" {
            val service = newService()
            service.findByNamespaceAndName(UUID.randomUUID(), "JIRA").shouldBeNull()
        }

        "findByNamespaceAndName is namespace-isolated" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()
            service.create(config(namespaceId = nsA, name = "JIRA"))

            service.findByNamespaceAndName(nsB, "JIRA").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // Upsert
        // -------------------------------------------------------------------------

        "upsert creates a new config when none exists" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val cfg = config(namespaceId = nsId, name = "JIRA", integrationType = "JIRA")

            val result = service.upsert(cfg)

            result.metadata.id shouldBe cfg.metadata.id
            service.findByParent(nsId) shouldHaveSize 1
        }

        "upsert updates existing config with same (namespaceId, name)" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val original = service.create(
                config(
                    namespaceId = nsId,
                    name = "JIRA",
                    integrationType = "JIRA",
                    parametersJson = """{"apiUrl": "https://old.atlassian.net"}""",
                )
            )

            val updated = service.upsert(
                config(
                    namespaceId = nsId,
                    name = "JIRA",
                    integrationType = "JIRA",
                    parametersJson = """{"apiUrl": "https://new.atlassian.net"}""",
                )
            )

            // Same entity id preserved
            updated.metadata.id shouldBe original.metadata.id
            // Parameters updated
            updated.parameters?.get("apiUrl")?.asText() shouldBe "https://new.atlassian.net"
            // No duplicate created
            service.findByParent(nsId) shouldHaveSize 1
        }

        "upsert with different name in same namespace creates a second config" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.upsert(config(namespaceId = nsId, name = "JIRA_PROD"))
            service.upsert(config(namespaceId = nsId, name = "JIRA_STAGING"))

            service.findByParent(nsId) shouldHaveSize 2
        }

        "upsert preserves existing entity id" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val first = service.upsert(config(namespaceId = nsId, name = "JIRA"))

            val second = service.upsert(config(namespaceId = nsId, name = "JIRA"))

            second.metadata.id shouldBe first.metadata.id
        }

        // -------------------------------------------------------------------------
        // Parameters (JsonNode)
        // -------------------------------------------------------------------------

        "config with null parameters is persisted and retrieved correctly" {
            val service = newService()
            val cfg = config(parametersJson = null)
            val saved = service.create(cfg)

            val found = service.findById(saved.metadata.id)
            found.shouldNotBeNull()
            found.parameters.shouldBeNull()
        }

        "config with nested JSON parameters is preserved" {
            val service = newService()
            val json = """{"apiUrl": "https://jira.example.com", "apiKey": "secret", "projects": ["PROJ", "DEV"]}"""
            val cfg = config(parametersJson = json)
            val saved = service.create(cfg)

            val found = service.findById(saved.metadata.id)
            found.shouldNotBeNull()
            found.parameters.shouldNotBeNull()
            found.parameters!!.get("apiUrl").asText() shouldBe "https://jira.example.com"
            found.parameters.get("projects").size() shouldBe 2
        }

        // -------------------------------------------------------------------------
        // Delete
        // -------------------------------------------------------------------------

        "delete soft-deletes the config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val cfg = service.create(config(namespaceId = nsId))

            service.delete(cfg.metadata.id) shouldBe true

            service.findById(cfg.metadata.id).shouldBeNull()
            service.findByParent(nsId).shouldBeEmpty()
        }

        "delete returns false for unknown id" {
            val service = newService()
            service.delete(UUID.randomUUID()) shouldBe false
        }

        "deleteByParent removes all configs for a namespace" {
            val service = newService()
            val nsId = UUID.randomUUID()

            service.create(config(namespaceId = nsId, name = "JIRA"))
            service.create(config(namespaceId = nsId, name = "SLACK"))

            val count = service.deleteByParent(nsId)
            count shouldBe 2
            service.findByParent(nsId).shouldBeEmpty()
        }

        "deleteByParent does not affect other namespaces" {
            val service = newService()
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            service.create(config(namespaceId = nsA, name = "JIRA"))
            service.create(config(namespaceId = nsB, name = "JIRA"))

            service.deleteByParent(nsA)

            service.findByParent(nsA).shouldBeEmpty()
            service.findByParent(nsB) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "update replaces the config" {
            val service = newService()
            val nsId = UUID.randomUUID()
            val original = service.create(config(namespaceId = nsId, name = "JIRA", integrationType = "JIRA"))

            val updated = service.update(original.copy(integrationType = "JIRA_V2"))

            updated.integrationType shouldBe "JIRA_V2"
            service.findById(original.metadata.id)?.integrationType shouldBe "JIRA_V2"
        }
    }
}

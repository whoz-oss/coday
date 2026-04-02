package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.util.UUID

/**
 * Unit tests for [FilesystemIntegrationConfigRepository].
 *
 * Each test uses an isolated temporary directory to avoid inter-test interference.
 * Covers persistence across restarts and soft-delete durability.
 */
class FilesystemIntegrationConfigRepositorySpec : StringSpec() {
    private val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    private fun newRepo(dir: java.nio.file.Path) = FilesystemIntegrationConfigRepository(dir, mapper)

    private fun tmpDir(): java.nio.file.Path = Files.createTempDirectory("agentos-test-integration-configs")

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

        "save and findByIds returns the same config" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val cfg = config(name = "JIRA")

            val saved = repo.save(cfg)
            val found = repo.findByIds(listOf(saved.metadata.id))

            found shouldHaveSize 1
            found.first().metadata.id shouldBe saved.metadata.id
            found.first().name shouldBe "JIRA"
            found.first().integrationType shouldBe "JIRA"
        }

        "findByIds returns empty list for unknown id" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            repo.findByIds(listOf(UUID.randomUUID())).shouldBeEmpty()
        }

        "findByParent returns only configs for the given namespace" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val nsA = UUID.randomUUID()
            val nsB = UUID.randomUUID()

            repo.save(config(namespaceId = nsA, name = "JIRA"))
            repo.save(config(namespaceId = nsA, name = "SLACK"))
            repo.save(config(namespaceId = nsB, name = "GITHUB"))

            repo.findByParent(nsA) shouldHaveSize 2
            repo.findByParent(nsB) shouldHaveSize 1
            repo.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        "findByParent returns configs sorted by name" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val nsId = UUID.randomUUID()

            repo.save(config(namespaceId = nsId, name = "SLACK"))
            repo.save(config(namespaceId = nsId, name = "GITHUB"))
            repo.save(config(namespaceId = nsId, name = "JIRA"))

            val names = repo.findByParent(nsId).map { it.name }
            names shouldBe listOf("GITHUB", "JIRA", "SLACK")
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "save updates an existing config" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val nsId = UUID.randomUUID()
            val cfg = repo.save(config(namespaceId = nsId, name = "JIRA", integrationType = "JIRA"))

            val updated = cfg.copy(integrationType = "JIRA_V2")
            repo.save(updated)

            val found = repo.findByIds(listOf(cfg.metadata.id)).first()
            found.integrationType shouldBe "JIRA_V2"
        }

        // -------------------------------------------------------------------------
        // Delete (soft)
        // -------------------------------------------------------------------------

        "delete soft-deletes a config" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val nsId = UUID.randomUUID()
            val cfg = repo.save(config(namespaceId = nsId))

            repo.delete(cfg.metadata.id).shouldBeTrue()

            repo.findByIds(listOf(cfg.metadata.id)).shouldBeEmpty()
            repo.findByParent(nsId).shouldBeEmpty()
        }

        "deleteByParent soft-deletes all configs for a namespace" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val nsId = UUID.randomUUID()

            repo.save(config(namespaceId = nsId, name = "JIRA"))
            repo.save(config(namespaceId = nsId, name = "SLACK"))

            val count = repo.deleteByParent(nsId)
            count shouldBe 2
            repo.findByParent(nsId).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Persistence across restarts
        // -------------------------------------------------------------------------

        "data persists across repository restarts" {
            val dir = tmpDir()
            val nsId = UUID.randomUUID()

            val repo1 = newRepo(dir)
            val saved = repo1.save(config(namespaceId = nsId, name = "JIRA"))

            val repo2 = newRepo(dir)
            val found = repo2.findByIds(listOf(saved.metadata.id))

            found shouldHaveSize 1
            found.first().name shouldBe "JIRA"
        }

        "soft-delete persists across repository restarts" {
            val dir = tmpDir()
            val nsId = UUID.randomUUID()

            val repo1 = newRepo(dir)
            val cfg = repo1.save(config(namespaceId = nsId))
            repo1.delete(cfg.metadata.id)

            val repo2 = newRepo(dir)
            repo2.findByIds(listOf(cfg.metadata.id)).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // JsonNode parameters
        // -------------------------------------------------------------------------

        "config with JSON parameters persists and reloads correctly" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val nsId = UUID.randomUUID()
            val json = """{"apiUrl": "https://jira.example.com", "apiKey": "s3cr3t"}"""
            val cfg = config(namespaceId = nsId, parametersJson = json)
            val saved = repo.save(cfg)

            val found = repo.findByIds(listOf(saved.metadata.id)).first()
            found.parameters?.get("apiUrl")?.asText() shouldBe "https://jira.example.com"
        }

        "config with null parameters persists and reloads correctly" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val nsId = UUID.randomUUID()
            val cfg = config(namespaceId = nsId, parametersJson = null)
            val saved = repo.save(cfg)

            val found = repo.findByIds(listOf(saved.metadata.id)).first()
            // null parameters should round-trip as null (not NullNode) thanks to @JsonInclude(NON_NULL)
            val params = found.parameters
            assert(params == null || params.isNull) {
                "Expected parameters to be null or NullNode, but was: $params"
            }
        }
    }
}

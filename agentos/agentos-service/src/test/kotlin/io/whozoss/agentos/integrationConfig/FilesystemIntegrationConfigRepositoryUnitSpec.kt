package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class FilesystemIntegrationConfigRepositoryUnitSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        fun tempDir(): Path = Files.createTempDirectory("integration-config-repo-test")

        fun writeYaml(
            dir: Path,
            filename: String,
            content: String,
        ) = dir.resolve(filename).also { Files.writeString(it, content) }

        fun integrationsDir(root: Path): Path = root.resolve("integrations").also { Files.createDirectories(it) }

        fun integrationYaml(
            name: String,
            integrationType: String,
            description: String? = null,
            parameters: String? = null,
        ) = buildString {
            appendLine("name: $name")
            appendLine("integrationType: $integrationType")
            description?.let { appendLine("description: $it") }
            parameters?.let {
                appendLine("parameters:")
                appendLine(it)
            }
        }

        fun persistedConfig(
            namespaceId: UUID,
            name: String,
            integrationType: String = "JIRA",
        ) = IntegrationConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            userId = null,
            name = name,
            integrationType = integrationType,
        )

        fun buildRepo(
            delegate: IntegrationConfigRepository,
            namespaceRepository: NamespaceRepository,
        ) = FilesystemIntegrationConfigRepository(
            delegate = delegate,
            namespaceRepository = namespaceRepository,
            ttl = Duration.ofMinutes(5),
        )

        fun nsRepoWith(
            namespaceId: UUID,
            configPath: String?,
        ): NamespaceRepository =
            mockk<NamespaceRepository>().also {
                every { it.findByIds(listOf(namespaceId)) } returns
                    listOf(
                        Namespace(
                            metadata = EntityMetadata(id = namespaceId),
                            name = "ns",
                            configPath = configPath,
                        ),
                    )
            }

        val namespaceId: UUID = UUID.randomUUID()

        // -------------------------------------------------------------------------
        // No configPath — pure delegation
        // -------------------------------------------------------------------------

        "findByNamespaceId delegates to underlying repository when namespace has no configPath" {
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, configPath = null)
            val persisted = listOf(persistedConfig(namespaceId, "JIRA"))

            every { delegate.findByNamespaceId(namespaceId) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldBe persisted
            verify(exactly = 1) { delegate.findByNamespaceId(namespaceId) }
        }

        "findByNamespaceId delegates to underlying repository when namespace is not found" {
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val persisted = listOf(persistedConfig(namespaceId, "JIRA"))

            every { delegate.findByNamespaceId(namespaceId) } returns persisted
            every { nsRepo.findByIds(listOf(namespaceId)) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldBe persisted
        }

        // -------------------------------------------------------------------------
        // configPath present — filesystem augmentation
        // -------------------------------------------------------------------------

        "findByNamespaceId returns filesystem configs when delegate has none" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))
            writeYaml(integrationsDir(root), "github.yaml", integrationYaml("GITHUB", "GITHUB"))

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldHaveSize 2
            result.map { it.name }.toSet() shouldBe setOf("JIRA", "GITHUB")
        }

        "findByNamespaceId sets namespaceId from the queried namespace on filesystem configs" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result.single().namespaceId shouldBe namespaceId
            result.single().userId shouldBe null
        }

        "findByNamespaceId maps all YAML fields to IntegrationConfig" {
            val root = tempDir()
            writeYaml(
                integrationsDir(root),
                "jira.yaml",
                integrationYaml(
                    name = "JIRA",
                    integrationType = "JIRA",
                    description = "Team Jira instance",
                    parameters = "  baseUrl: https://company.atlassian.net",
                ),
            )

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId).single()

            result.name shouldBe "JIRA"
            result.integrationType shouldBe "JIRA"
            result.description shouldBe "Team Jira instance"
            result.parameters?.get("baseUrl")?.textValue() shouldBe "https://company.atlassian.net"
        }

        "findByNamespaceId uses stable UUID derived from integration name" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val repo1 = buildRepo(delegate, nsRepo)
            val id1 = repo1.findByNamespaceId(namespaceId).single().id

            val nsRepo2 = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()
            val repo2 = FilesystemIntegrationConfigRepository(delegate, nsRepo2, ttl = Duration.ZERO)
            val id2 = repo2.findByNamespaceId(namespaceId).single().id

            id1 shouldBe id2
        }

        // -------------------------------------------------------------------------
        // Merge: persisted configs win over filesystem
        // -------------------------------------------------------------------------

        "findByNamespaceId places persisted configs first then filesystem additions" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))
            writeYaml(integrationsDir(root), "github.yaml", integrationYaml("GITHUB", "GITHUB"))

            val persisted = listOf(persistedConfig(namespaceId, "FILES", integrationType = "FILES"))
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldHaveSize 3
            result.first().name shouldBe "FILES" // persisted first
            result.drop(1).map { it.name } shouldBe listOf("GITHUB", "JIRA") // filesystem sorted
        }

        "findByNamespaceId drops filesystem config when persisted config has same name (case-insensitive)" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA", description = "filesystem version"))

            val persisted = listOf(persistedConfig(namespaceId, "jira", integrationType = "JIRA"))
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldHaveSize 1
            result.single().description shouldBe null // persisted version kept (no description)
        }

        "findByNamespaceId returns only persisted when integrations directory does not exist" {
            val root = tempDir() // no integrations/ subdirectory

            val persisted = listOf(persistedConfig(namespaceId, "JIRA"))
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldBe persisted
        }

        // -------------------------------------------------------------------------
        // YAML robustness
        // -------------------------------------------------------------------------

        "findByNamespaceId skips YAML file with blank name and loads the rest" {
            val root = tempDir()
            val dir = integrationsDir(root)
            writeYaml(dir, "good.yaml", integrationYaml("JIRA", "JIRA"))
            writeYaml(dir, "blank.yaml", "name: \nintegrationType: JIRA")

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "JIRA"
        }

        "findByNamespaceId skips YAML file with blank integrationType and loads the rest" {
            val root = tempDir()
            val dir = integrationsDir(root)
            writeYaml(dir, "good.yaml", integrationYaml("JIRA", "JIRA"))
            writeYaml(dir, "notype.yaml", "name: NOTYPE\nintegrationType: ")

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "JIRA"
        }

        "findByNamespaceId ignores unknown YAML fields without error" {
            val root = tempDir()
            writeYaml(
                integrationsDir(root),
                "jira.yaml",
                """
                name: JIRA
                integrationType: JIRA
                description: Has extra fields
                unknownField: ignored
                anotherUnknown: 42
                """.trimIndent(),
            )

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByNamespaceId(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "JIRA"
            result.single().integrationType shouldBe "JIRA"
        }

        // -------------------------------------------------------------------------
        // findByParent — delegates to findByNamespaceId
        // -------------------------------------------------------------------------

        "findByParent delegates to findByNamespaceId" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByNamespaceId(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "JIRA"
        }

        // -------------------------------------------------------------------------
        // findAllForNamespaceIdAndUserId — filesystem participates in merge
        // -------------------------------------------------------------------------

        "findAllForNamespaceIdAndUserId includes filesystem configs in the result" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findAllForNamespaceIdAndUserId(namespaceId, null) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findAllForNamespaceIdAndUserId(namespaceId, null)

            result shouldHaveSize 1
            result.single().name shouldBe "JIRA"
            result.single().namespaceId shouldBe namespaceId
        }

        "findAllForNamespaceIdAndUserId does not add filesystem when namespaceId is null" {
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val userId = UUID.randomUUID()
            val userConfig = IntegrationConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = null,
                userId = userId,
                name = "JIRA",
                integrationType = "JIRA",
            )
            every { delegate.findAllForNamespaceIdAndUserId(null, userId) } returns listOf(userConfig)

            val result = buildRepo(delegate, nsRepo).findAllForNamespaceIdAndUserId(null, userId)

            result shouldBe listOf(userConfig)
        }

        "findAllForNamespaceIdAndUserId excludes filesystem when persisted ns-shared has same name" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA", description = "filesystem"))

            val persisted = persistedConfig(namespaceId, "JIRA")
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findAllForNamespaceIdAndUserId(namespaceId, null) } returns listOf(persisted)

            val result = buildRepo(delegate, nsRepo).findAllForNamespaceIdAndUserId(namespaceId, null)

            result shouldHaveSize 1
            result.single().description shouldBe null // persisted version, not filesystem
        }

        // -------------------------------------------------------------------------
        // findByTriple — filesystem fallback for namespace-shared scope
        // -------------------------------------------------------------------------

        "findByTriple returns persisted config when found" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))

            val persisted = persistedConfig(namespaceId, "JIRA")
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByTriple(namespaceId, null, "JIRA") } returns persisted

            val result = buildRepo(delegate, nsRepo).findByTriple(namespaceId, null, "JIRA")

            result shouldBe persisted
        }

        "findByTriple falls back to filesystem when delegate returns null" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByTriple(namespaceId, null, "JIRA") } returns null

            val result = buildRepo(delegate, nsRepo).findByTriple(namespaceId, null, "JIRA")

            result?.name shouldBe "JIRA"
            result?.namespaceId shouldBe namespaceId
        }

        "findByTriple returns null when name not found in filesystem either" {
            val root = tempDir()
            writeYaml(integrationsDir(root), "jira.yaml", integrationYaml("JIRA", "JIRA"))

            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByTriple(namespaceId, null, "GITHUB") } returns null

            val result = buildRepo(delegate, nsRepo).findByTriple(namespaceId, null, "GITHUB")

            result shouldBe null
        }

        "findByTriple does not fall back to filesystem for user-scoped triple" {
            val userId = UUID.randomUUID()
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.findByTriple(namespaceId, userId, "JIRA") } returns null

            val result = buildRepo(delegate, nsRepo).findByTriple(namespaceId, userId, "JIRA")

            result shouldBe null
            // nsRepo should never be called — user-scoped triples have no filesystem backing
            verify(exactly = 0) { nsRepo.findByIds(any()) }
        }

        "findByTriple does not fall back to filesystem when namespaceId is null" {
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.findByTriple(null, null, "JIRA") } returns null

            val result = buildRepo(delegate, nsRepo).findByTriple(null, null, "JIRA")

            result shouldBe null
            verify(exactly = 0) { nsRepo.findByIds(any()) }
        }

        // -------------------------------------------------------------------------
        // Write operations delegated unchanged
        // -------------------------------------------------------------------------

        "save delegates to underlying repository" {
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val config = persistedConfig(namespaceId, "JIRA")
            every { delegate.save(config) } returns config

            val result = buildRepo(delegate, nsRepo).save(config)

            result shouldBe config
            verify(exactly = 1) { delegate.save(config) }
        }

        "delete delegates to underlying repository" {
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val id = UUID.randomUUID()
            every { delegate.delete(id) } returns true

            val result = buildRepo(delegate, nsRepo).delete(id)

            result shouldBe true
            verify(exactly = 1) { delegate.delete(id) }
        }

        "deleteByParent delegates to underlying repository" {
            val delegate = mockk<IntegrationConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.deleteByParent(namespaceId) } returns 2

            val result = buildRepo(delegate, nsRepo).deleteByParent(namespaceId)

            result shouldBe 2
            verify(exactly = 1) { delegate.deleteByParent(namespaceId) }
        }
    })

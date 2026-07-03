package io.whozoss.agentos.agentConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
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

class FilesystemAgentConfigRepositoryUnitSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        fun tempDir(): Path = Files.createTempDirectory("agent-config-repo-test")

        fun writeYaml(
            dir: Path,
            filename: String,
            content: String,
        ) = dir.resolve(filename).also { Files.writeString(it, content) }

        fun agentsDir(root: Path): Path = root.resolve("agents").also { Files.createDirectories(it) }

        fun agentYaml(
            name: String,
            description: String? = null,
            instructions: String? = null,
            modelName: String? = null,
        ) = buildString {
            appendLine("name: $name")
            description?.let { appendLine("description: $it") }
            instructions?.let {
                appendLine("instructions: |")
                appendLine("  $it")
            }
            modelName?.let { appendLine("modelName: $it") }
        }

        fun persistedConfig(
            namespaceId: UUID,
            name: String,
            modelName: String? = "BIG",
        ) = AgentConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            name = name,
            modelName = modelName,
        )

        fun buildRepo(
            delegate: AgentConfigRepository,
            namespaceRepository: NamespaceRepository,
        ) = FilesystemAgentConfigRepository(
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

        "findByParent delegates to underlying repository when namespace has no configPath" {
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, configPath = null)
            val persisted = listOf(persistedConfig(namespaceId, "Alpha"))

            every { delegate.findByParent(namespaceId, withDisabled = true) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldBe persisted
            verify(exactly = 1) { delegate.findByParent(namespaceId, withDisabled = true) }
        }

        "findByParent delegates to underlying repository when namespace is not found" {
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val persisted = listOf(persistedConfig(namespaceId, "Alpha"))

            every { delegate.findByParent(namespaceId, withDisabled = true) } returns persisted
            every { nsRepo.findByIds(listOf(namespaceId)) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldBe persisted
        }

        // -------------------------------------------------------------------------
        // configPath present — filesystem augmentation
        // -------------------------------------------------------------------------

        "findByParent returns filesystem configs when delegate has none" {
            val root = tempDir()
            val agentsDir = agentsDir(root)
            writeYaml(agentsDir, "dev.yaml", agentYaml("Dev", modelName = "BIG"))
            writeYaml(agentsDir, "reviewer.yaml", agentYaml("Reviewer", modelName = "SMALL"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 2
            result.map { it.name }.toSet() shouldBe setOf("Dev", "Reviewer")
        }

        "findByParent sets namespaceId from parentId on filesystem configs" {
            val root = tempDir()
            writeYaml(agentsDir(root), "dev.yaml", agentYaml("Dev"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result.single().namespaceId shouldBe namespaceId
        }

        "findByParent maps all YAML fields to AgentConfig" {
            val root = tempDir()
            writeYaml(
                agentsDir(root),
                "dev.yaml",
                agentYaml("Dev", description = "Backend specialist", modelName = "BIG"),
            )

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId).single()

            result.name shouldBe "Dev"
            result.description shouldBe "Backend specialist"
            result.modelName shouldBe "BIG"
        }

        "findByParent uses stable UUID derived from agent name" {
            val root = tempDir()
            writeYaml(agentsDir(root), "dev.yaml", agentYaml("Dev"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val repo = buildRepo(delegate, nsRepo)
            val id1 = repo.findByParent(namespaceId).single().id
            // force TTL expiry by using a zero-TTL repo on the same directory
            val nsRepo2 = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()
            val repo2 = FilesystemAgentConfigRepository(delegate, nsRepo2, ttl = Duration.ZERO)
            val id2 = repo2.findByParent(namespaceId).single().id

            id1 shouldBe id2
        }

        // -------------------------------------------------------------------------
        // Merge: persisted configs win over filesystem
        // -------------------------------------------------------------------------

        "findByParent places persisted configs first then filesystem additions" {
            val root = tempDir()
            writeYaml(agentsDir(root), "alpha.yaml", agentYaml("Alpha"))
            writeYaml(agentsDir(root), "beta.yaml", agentYaml("Beta"))

            val persisted = listOf(persistedConfig(namespaceId, "Gamma"))
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 3
            result.first().name shouldBe "Gamma" // persisted first
            result.drop(1).map { it.name } shouldBe listOf("Alpha", "Beta") // filesystem sorted
        }

        "findByParent drops filesystem config when persisted config has same name (case-insensitive)" {
            val root = tempDir()
            // filesystem has "Dev", persisted has "dev" — persisted wins
            writeYaml(agentsDir(root), "dev.yaml", agentYaml("Dev", modelName = "SMALL"))

            val persisted = listOf(persistedConfig(namespaceId, "dev", modelName = "BIG"))
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 1
            result.single().modelName shouldBe "BIG" // persisted version kept
        }

        "findByParent returns only persisted when agents directory does not exist" {
            val root = tempDir() // no agents/ subdirectory created

            val persisted = listOf(persistedConfig(namespaceId, "Alpha"))
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldBe persisted
        }

        // -------------------------------------------------------------------------
        // YAML robustness
        // -------------------------------------------------------------------------

        "findByParent skips YAML file with blank name and loads the rest" {
            val root = tempDir()
            val dir = agentsDir(root)
            writeYaml(dir, "good.yaml", agentYaml("Good Agent"))
            writeYaml(dir, "blank.yaml", "name: \n") // blank name

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "Good Agent"
        }

        "findByParent ignores unknown YAML fields without error" {
            val root = tempDir()
            writeYaml(
                agentsDir(root),
                "coday-style.yaml",
                """
                name: Coday Agent
                description: Has extra fields
                modelName: BIG
                aiProvider: anthropic
                integrations:
                  FILES:
                  GIT:
                mandatoryDocs:
                  - ./docs/foo.md
                """.trimIndent(),
            )

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            val agent = result.single()
            agent.name shouldBe "Coday Agent"
            agent.modelName shouldBe "BIG"
        }

        // -------------------------------------------------------------------------
        // integrations field
        // -------------------------------------------------------------------------

        "findByParent maps integrations with null list (all tools allowed)" {
            val root = tempDir()
            writeYaml(
                agentsDir(root),
                "dev.yaml",
                """
                name: Dev
                integrations:
                  FILES:
                  GIT:
                """.trimIndent(),
            )

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId).single()

            val integrations = result.integrations.shouldNotBeNull()
            integrations shouldBe mapOf("FILES" to null, "GIT" to null)
        }

        "findByParent maps integrations with non-null list (restricted tools)" {
            val root = tempDir()
            writeYaml(
                agentsDir(root),
                "reviewer.yaml",
                """
                name: Reviewer
                integrations:
                  GITHUB:
                    - add_issue_comment
                    - pull_request_read
                  FILES:
                """.trimIndent(),
            )

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId).single()

            val integrations = result.integrations.shouldNotBeNull()
            integrations["GITHUB"] shouldBe listOf("add_issue_comment", "pull_request_read")
            integrations["FILES"] shouldBe null
        }

        "findByParent sets integrations to null when YAML has no integrations field" {
            val root = tempDir()
            writeYaml(agentsDir(root), "simple.yaml", agentYaml("Simple Agent"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = true) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId).single()

            result.integrations shouldBe null
        }

        // -------------------------------------------------------------------------
        // findAvailableByNamespaceIdAndUserId — filesystem augmentation
        // -------------------------------------------------------------------------

        "findAvailableByNamespaceIdAndUserId delegates to underlying repository when namespace has no configPath" {
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, configPath = null)
            val userId = UUID.randomUUID()
            val persisted = listOf(persistedConfig(namespaceId, "Alpha"))
            every { delegate.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null) } returns persisted

            val result = buildRepo(delegate, nsRepo).findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null)

            result shouldBe persisted
            verify(exactly = 1) { delegate.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null) }
        }

        "findAvailableByNamespaceIdAndUserId includes filesystem agents not returned by delegate" {
            val root = tempDir()
            writeYaml(agentsDir(root), "fs-agent.yaml", agentYaml("FsAgent"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            val userId = UUID.randomUUID()
            every { delegate.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null)

            result shouldHaveSize 1
            result.single().name shouldBe "FsAgent"
            result.single().namespaceId shouldBe namespaceId
        }

        "findAvailableByNamespaceIdAndUserId merges delegate and filesystem results, persisted wins on name collision" {
            val root = tempDir()
            writeYaml(agentsDir(root), "alpha.yaml", agentYaml("Alpha", modelName = "SMALL"))
            writeYaml(agentsDir(root), "beta.yaml", agentYaml("Beta"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            val userId = UUID.randomUUID()
            val persistedAlpha = persistedConfig(namespaceId, "Alpha", modelName = "BIG")
            every { delegate.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null) } returns listOf(persistedAlpha)

            val result = buildRepo(delegate, nsRepo).findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null)

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("Alpha", "Beta")
            result.first { it.name == "Alpha" }.modelName shouldBe "BIG" // persisted wins
        }

        "findAvailableByNamespaceIdAndUserId filters filesystem results by agentName prefix" {
            val root = tempDir()
            writeYaml(agentsDir(root), "alpha.yaml", agentYaml("Alpha"))
            writeYaml(agentsDir(root), "alpha-two.yaml", agentYaml("Alpha-Two"))
            writeYaml(agentsDir(root), "beta.yaml", agentYaml("Beta"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            val userId = UUID.randomUUID()
            every { delegate.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "Alpha") } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "Alpha")

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("Alpha", "Alpha-Two")
        }

        "findAvailableByNamespaceIdAndUserId agentName prefix filter on filesystem agents is case-insensitive" {
            val root = tempDir()
            writeYaml(agentsDir(root), "my-agent.yaml", agentYaml("My-Agent"))
            writeYaml(agentsDir(root), "my-other.yaml", agentYaml("My-Other"))
            writeYaml(agentsDir(root), "unrelated.yaml", agentYaml("Unrelated"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            val userId = UUID.randomUUID()
            every { delegate.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "MY-") } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "MY-")

            result.map { it.name } shouldContainExactlyInAnyOrder listOf("My-Agent", "My-Other")
        }

        "findAvailableByNamespaceIdAndUserId returns empty when agentName prefix matches nothing" {
            val root = tempDir()
            writeYaml(agentsDir(root), "alpha.yaml", agentYaml("Alpha"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            val userId = UUID.randomUUID()
            every { delegate.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "nonexistent") } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, "nonexistent")

            result.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Write operations delegated unchanged
        // -------------------------------------------------------------------------

        "save delegates to underlying repository" {
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val config = persistedConfig(namespaceId, "Alpha")
            every { delegate.save(config) } returns config

            val result = buildRepo(delegate, nsRepo).save(config)

            result shouldBe config
            verify(exactly = 1) { delegate.save(config) }
        }

        "delete delegates to underlying repository" {
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val id = UUID.randomUUID()
            every { delegate.delete(id) } returns true

            val result = buildRepo(delegate, nsRepo).delete(id)

            result shouldBe true
            verify(exactly = 1) { delegate.delete(id) }
        }

        "deleteByParent delegates to underlying repository" {
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.deleteByParent(namespaceId) } returns 3

            val result = buildRepo(delegate, nsRepo).deleteByParent(namespaceId)

            result shouldBe 3
            verify(exactly = 1) { delegate.deleteByParent(namespaceId) }
        }

        // -------------------------------------------------------------------------
        // findByParent with withDisabled=false (enabled-only)
        // -------------------------------------------------------------------------

        "findByParent with withDisabled=false forwards withDisabled to delegate" {
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, configPath = null)
            val persisted = listOf(persistedConfig(namespaceId, "Alpha"))

            every { delegate.findByParent(namespaceId, withDisabled = false) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId, withDisabled = false)

            result shouldBe persisted
            verify(exactly = 1) { delegate.findByParent(namespaceId, withDisabled = false) }
        }

        "findByParent with withDisabled=false still merges filesystem agents" {
            val root = tempDir()
            writeYaml(agentsDir(root), "dev.yaml", agentYaml("Dev", modelName = "BIG"))

            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = false) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId, withDisabled = false)

            result shouldHaveSize 1
            result.single().name shouldBe "Dev"
        }

        "findByParent with withDisabled=false drops filesystem config when persisted has same name" {
            val root = tempDir()
            writeYaml(agentsDir(root), "dev.yaml", agentYaml("Dev", modelName = "SMALL"))

            val persisted = listOf(persistedConfig(namespaceId, "dev", modelName = "BIG"))
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = false) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId, withDisabled = false)

            result shouldHaveSize 1
            result.single().modelName shouldBe "BIG"
        }

        "findByParent with withDisabled=false places persisted first then filesystem" {
            val root = tempDir()
            writeYaml(agentsDir(root), "alpha.yaml", agentYaml("Alpha"))
            writeYaml(agentsDir(root), "beta.yaml", agentYaml("Beta"))

            val persisted = listOf(persistedConfig(namespaceId, "Gamma"))
            val delegate = mockk<AgentConfigRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId, withDisabled = false) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId, withDisabled = false)

            result shouldHaveSize 3
            result.first().name shouldBe "Gamma"
            result.drop(1).map { it.name } shouldBe listOf("Alpha", "Beta")
        }
    })

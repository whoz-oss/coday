package io.whozoss.agentos.prompt

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

class FilesystemPromptRepositoryUnitSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------------

        fun tempDir(): Path = Files.createTempDirectory("prompt-repo-test")

        fun writeYaml(
            dir: Path,
            filename: String,
            content: String,
        ) = dir.resolve(filename).also { Files.writeString(it, content) }

        fun promptsDir(root: Path): Path = root.resolve("prompts").also { Files.createDirectories(it) }

        fun promptYaml(
            name: String,
            description: String? = null,
            content: List<String> = listOf("Do the thing"),
        ) = buildString {
            appendLine("name: $name")
            description?.let { appendLine("description: $it") }
            appendLine("content:")
            content.forEach { appendLine("  - \"$it\"") }
        }

        fun persistedPrompt(
            namespaceId: UUID?,
            name: String,
            userId: UUID? = null,
            content: List<String> = listOf("Persisted content"),
        ) = Prompt(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            userId = userId,
            name = name,
            content = content,
        )

        fun buildRepo(
            delegate: PromptRepository,
            namespaceRepository: NamespaceRepository,
        ) = FilesystemPromptRepository(
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
            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, configPath = null)
            val persisted = listOf(persistedPrompt(namespaceId, "plan"))

            every { delegate.findByParent(namespaceId) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldBe persisted
            verify(exactly = 1) { delegate.findByParent(namespaceId) }
        }

        "findByParent delegates to underlying repository when namespace is not found" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val persisted = listOf(persistedPrompt(namespaceId, "plan"))

            every { delegate.findByParent(namespaceId) } returns persisted
            every { nsRepo.findByIds(listOf(namespaceId)) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldBe persisted
        }

        // -------------------------------------------------------------------------
        // configPath present — filesystem augmentation
        // -------------------------------------------------------------------------

        "findByParent returns filesystem prompts when delegate has none" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))
            writeYaml(promptsDir(root), "review.yaml", promptYaml("review"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 2
            result.map { it.name }.toSet() shouldBe setOf("plan", "review")
        }

        "findByParent sets namespaceId from parentId on filesystem prompts" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result.single().namespaceId shouldBe namespaceId
            result.single().userId shouldBe null
            result.single().agentConfigId shouldBe null
        }

        "findByParent maps all YAML fields to Prompt" {
            val root = tempDir()
            writeYaml(
                promptsDir(root),
                "plan.yaml",
                promptYaml("plan", description = "Create an implementation plan", content = listOf("Step one", "Step two")),
            )

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId).single()

            result.name shouldBe "plan"
            result.description shouldBe "Create an implementation plan"
            result.content shouldBe listOf("Step one", "Step two")
        }

        "findByParent uses stable UUID derived from prompt name" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val repo = buildRepo(delegate, nsRepo)
            val id1 = repo.findByParent(namespaceId).single().id
            val nsRepo2 = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()
            val repo2 = FilesystemPromptRepository(delegate, nsRepo2, ttl = Duration.ZERO)
            val id2 = repo2.findByParent(namespaceId).single().id

            id1 shouldBe id2
        }

        // -------------------------------------------------------------------------
        // Merge: persisted prompts win over filesystem
        // -------------------------------------------------------------------------

        "findByParent places persisted prompts first then filesystem additions" {
            val root = tempDir()
            writeYaml(promptsDir(root), "alpha.yaml", promptYaml("alpha"))
            writeYaml(promptsDir(root), "beta.yaml", promptYaml("beta"))

            val persisted = listOf(persistedPrompt(namespaceId, "gamma"))
            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 3
            result.first().name shouldBe "gamma" // persisted first
            result.drop(1).map { it.name } shouldBe listOf("alpha", "beta") // filesystem sorted
        }

        "findByParent drops filesystem prompt when persisted prompt has same name (case-insensitive)" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("Plan", content = listOf("filesystem content")))

            val persisted = listOf(persistedPrompt(namespaceId, "plan", content = listOf("persisted content")))
            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 1
            result.single().content shouldBe listOf("persisted content")
        }

        "findByParent returns only persisted when prompts directory does not exist" {
            val root = tempDir() // no prompts/ subdirectory created

            val persisted = listOf(persistedPrompt(namespaceId, "plan"))
            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns persisted

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldBe persisted
        }

        // -------------------------------------------------------------------------
        // YAML robustness
        // -------------------------------------------------------------------------

        "findByParent skips YAML file with blank name and loads the rest" {
            val root = tempDir()
            val dir = promptsDir(root)
            writeYaml(dir, "good.yaml", promptYaml("good-prompt"))
            writeYaml(dir, "blank.yaml", "name: \ncontent:\n  - hello\n")

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "good-prompt"
        }

        "findByParent skips YAML file with missing content" {
            val root = tempDir()
            val dir = promptsDir(root)
            writeYaml(dir, "good.yaml", promptYaml("good-prompt"))
            writeYaml(dir, "nocontent.yaml", "name: no-content\n")

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "good-prompt"
        }

        "findByParent skips YAML file with a blank content element" {
            val root = tempDir()
            val dir = promptsDir(root)
            writeYaml(dir, "good.yaml", promptYaml("good-prompt"))
            writeYaml(dir, "blankcontent.yaml", "name: blank-content\ncontent:\n  - \"\"\n")

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "good-prompt"
        }

        "findByParent ignores unknown YAML fields without error" {
            val root = tempDir()
            writeYaml(
                promptsDir(root),
                "plan.yaml",
                """
                name: plan
                description: Has extra fields
                content:
                  - "Do the thing"
                agentName: Dev
                unknownField: ignored
                """.trimIndent(),
            )

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            val prompt = result.single()
            prompt.name shouldBe "plan"
            prompt.description shouldBe "Has extra fields"
        }

        // -------------------------------------------------------------------------
        // parameters field
        // -------------------------------------------------------------------------

        "findByParent maps parameters from YAML" {
            val root = tempDir()
            writeYaml(
                promptsDir(root),
                "plan.yaml",
                """
                name: plan
                content:
                  - "Plan for {{scope}}"
                parameters:
                  - name: scope
                    description: what to plan
                    defaultValue: ""
                """.trimIndent(),
            )

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId).single()

            result.parameters shouldHaveSize 1
            result.parameters.single().name shouldBe "scope"
            result.parameters.single().description shouldBe "what to plan"
            result.parameters.single().defaultValue shouldBe ""
        }

        "findByParent skips YAML file with duplicate parameter names" {
            val root = tempDir()
            val dir = promptsDir(root)
            writeYaml(dir, "good.yaml", promptYaml("good-prompt"))
            writeYaml(
                dir,
                "dup.yaml",
                """
                name: dup-prompt
                content:
                  - "hello {{scope}}"
                parameters:
                  - name: scope
                    defaultValue: "a"
                  - name: scope
                    defaultValue: "b"
                """.trimIndent(),
            )

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId)

            result shouldHaveSize 1
            result.single().name shouldBe "good-prompt"
        }

        "findByParent sets parameters to empty when YAML has no parameters field" {
            val root = tempDir()
            writeYaml(promptsDir(root), "simple.yaml", promptYaml("simple-prompt"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByParent(namespaceId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByParent(namespaceId).single()

            result.parameters.shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // findEffective — filesystem participates in the 4-tier merge
        // -------------------------------------------------------------------------

        "findEffective includes filesystem prompts in the result" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            val userId = UUID.randomUUID()
            every { delegate.findEffective(namespaceId, userId) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findEffective(namespaceId, userId)

            result shouldHaveSize 1
            result.single().name shouldBe "plan"
            result.single().namespaceId shouldBe namespaceId
        }

        "findEffective excludes filesystem prompt when persisted ns-shared has same name" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan", content = listOf("filesystem")))

            val persisted = persistedPrompt(namespaceId, "plan", content = listOf("persisted"))
            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            val userId = UUID.randomUUID()
            every { delegate.findEffective(namespaceId, userId) } returns listOf(persisted)

            val result = buildRepo(delegate, nsRepo).findEffective(namespaceId, userId)

            result shouldHaveSize 1
            result.single().content shouldBe listOf("persisted")
        }

        "findEffective keeps user-global and user×namespace persisted layers untouched" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val userId = UUID.randomUUID()
            val userGlobal = persistedPrompt(namespaceId = null, name = "personal", userId = userId)
            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findEffective(namespaceId, userId) } returns listOf(userGlobal)

            val result = buildRepo(delegate, nsRepo).findEffective(namespaceId, userId)

            result shouldHaveSize 2
            result.map { it.name }.toSet() shouldBe setOf("personal", "plan")
        }

        // -------------------------------------------------------------------------
        // findByTriple — filesystem fallback for namespace-shared scope
        // -------------------------------------------------------------------------

        "findByTriple returns persisted prompt when found" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val persisted = persistedPrompt(namespaceId, "plan")
            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByTriple(namespaceId, null, "plan") } returns persisted

            val result = buildRepo(delegate, nsRepo).findByTriple(namespaceId, null, "plan")

            result shouldBe persisted
        }

        "findByTriple falls back to filesystem when delegate returns null" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByTriple(namespaceId, null, "plan") } returns null

            val result = buildRepo(delegate, nsRepo).findByTriple(namespaceId, null, "plan")

            result?.name shouldBe "plan"
            result?.namespaceId shouldBe namespaceId
        }

        "findByTriple returns null when name not found in filesystem either" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByTriple(namespaceId, null, "review") } returns null

            val result = buildRepo(delegate, nsRepo).findByTriple(namespaceId, null, "review")

            result shouldBe null
        }

        "findByTriple does not fall back to filesystem for user-scoped triple" {
            val userId = UUID.randomUUID()
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.findByTriple(namespaceId, userId, "plan") } returns null

            val result = buildRepo(delegate, nsRepo).findByTriple(namespaceId, userId, "plan")

            result shouldBe null
            verify(exactly = 0) { nsRepo.findByIds(any()) }
        }

        "findByTriple does not fall back to filesystem when namespaceId is null" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.findByTriple(null, null, "plan") } returns null

            val result = buildRepo(delegate, nsRepo).findByTriple(null, null, "plan")

            result shouldBe null
            verify(exactly = 0) { nsRepo.findByIds(any()) }
        }

        // -------------------------------------------------------------------------
        // findByIds — filesystem augmentation
        // -------------------------------------------------------------------------

        "findByIds returns delegate result when all ids are found in Neo4j" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val prompt = persistedPrompt(namespaceId, "plan")
            every { delegate.findByIds(listOf(prompt.id), withRemoved = false) } returns listOf(prompt)

            val result = buildRepo(delegate, nsRepo).findByIds(listOf(prompt.id), withRemoved = false)

            result shouldBe listOf(prompt)
            verify(exactly = 0) { nsRepo.findByParent(any<String>()) }
        }

        "findByIds resolves a filesystem prompt id that Neo4j does not know" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val fsId = UUID.nameUUIDFromBytes("filesystem-prompt:plan".toByteArray())

            every { delegate.findByIds(listOf(fsId), withRemoved = false) } returns emptyList()
            every { nsRepo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns
                listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = root.toString()))
            every { nsRepo.findByIds(listOf(namespaceId)) } returns
                listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = root.toString()))

            val result = buildRepo(delegate, nsRepo).findByIds(listOf(fsId), withRemoved = false)

            result shouldHaveSize 1
            result.single().name shouldBe "plan"
            result.single().namespaceId shouldBe namespaceId
            result.single().id shouldBe fsId
        }

        "findByIds returns empty when id is unknown to both Neo4j and filesystem" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val unknownId = UUID.randomUUID()

            every { delegate.findByIds(listOf(unknownId), withRemoved = false) } returns emptyList()
            every { nsRepo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns
                listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = root.toString()))
            every { nsRepo.findByIds(listOf(namespaceId)) } returns
                listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = root.toString()))

            val result = buildRepo(delegate, nsRepo).findByIds(listOf(unknownId), withRemoved = false)

            result.shouldBeEmpty()
        }

        "findByIds skips namespaces without configPath when scanning for missing ids" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val nsWithPath = Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = root.toString())
            val nsWithoutPath = Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "no-path", configPath = null)
            val fsId = UUID.nameUUIDFromBytes("filesystem-prompt:plan".toByteArray())

            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.findByIds(listOf(fsId), withRemoved = false) } returns emptyList()
            every { nsRepo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns listOf(nsWithPath, nsWithoutPath)
            every { nsRepo.findByIds(listOf(namespaceId)) } returns listOf(nsWithPath)

            val result = buildRepo(delegate, nsRepo).findByIds(listOf(fsId), withRemoved = false)

            result shouldHaveSize 1
            result.single().name shouldBe "plan"
        }

        // -------------------------------------------------------------------------
        // findByScope — filesystem participates only for the namespace-shared scope
        // -------------------------------------------------------------------------

        "findByScope includes filesystem prompts for namespace-shared scope with no agentConfigIds filter" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByScope(namespaceId, null, null) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByScope(namespaceId, null, null)

            result shouldHaveSize 1
            result.single().name shouldBe "plan"
        }

        "findByScope does not add filesystem prompts when agentConfigIds filter is present" {
            val root = tempDir()
            writeYaml(promptsDir(root), "plan.yaml", promptYaml("plan"))

            val agentConfigId = UUID.randomUUID()
            val delegate = mockk<PromptRepository>()
            val nsRepo = nsRepoWith(namespaceId, root.toString())
            every { delegate.findByScope(namespaceId, null, listOf(agentConfigId)) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByScope(namespaceId, null, listOf(agentConfigId))

            result.shouldBeEmpty()
        }

        "findByScope does not add filesystem prompts when userId is not null" {
            val userId = UUID.randomUUID()
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.findByScope(namespaceId, userId, null) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByScope(namespaceId, userId, null)

            result.shouldBeEmpty()
            verify(exactly = 0) { nsRepo.findByIds(any()) }
        }

        "findByScope does not add filesystem prompts when namespaceId is null (platform scope)" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.findByScope(null, null, null) } returns emptyList()

            val result = buildRepo(delegate, nsRepo).findByScope(null, null, null)

            result.shouldBeEmpty()
            verify(exactly = 0) { nsRepo.findByIds(any()) }
        }

        // -------------------------------------------------------------------------
        // Unaugmented pass-through operations
        // -------------------------------------------------------------------------

        "findPlatform delegates to underlying repository unchanged" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val platform = listOf(persistedPrompt(namespaceId = null, name = "welcome"))
            every { delegate.findPlatform() } returns platform

            val result = buildRepo(delegate, nsRepo).findPlatform()

            result shouldBe platform
            verify(exactly = 1) { delegate.findPlatform() }
        }

        "findByUserId delegates to underlying repository unchanged" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val userId = UUID.randomUUID()
            val userPrompts = listOf(persistedPrompt(namespaceId = null, name = "personal", userId = userId))
            every { delegate.findByUserId(userId) } returns userPrompts

            val result = buildRepo(delegate, nsRepo).findByUserId(userId)

            result shouldBe userPrompts
            verify(exactly = 1) { delegate.findByUserId(userId) }
        }

        "save delegates to underlying repository" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val prompt = persistedPrompt(namespaceId, "plan")
            every { delegate.save(prompt) } returns prompt

            val result = buildRepo(delegate, nsRepo).save(prompt)

            result shouldBe prompt
            verify(exactly = 1) { delegate.save(prompt) }
        }

        "delete delegates to underlying repository" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val id = UUID.randomUUID()
            every { delegate.delete(id) } returns true

            val result = buildRepo(delegate, nsRepo).delete(id)

            result shouldBe true
            verify(exactly = 1) { delegate.delete(id) }
        }

        "deleteByParent delegates to underlying repository" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            every { delegate.deleteByParent(namespaceId) } returns 2

            val result = buildRepo(delegate, nsRepo).deleteByParent(namespaceId)

            result shouldBe 2
            verify(exactly = 1) { delegate.deleteByParent(namespaceId) }
        }

        "softDeleteByAgentConfigId delegates to underlying repository" {
            val delegate = mockk<PromptRepository>()
            val nsRepo = mockk<NamespaceRepository>()
            val agentConfigId = UUID.randomUUID()
            every { delegate.softDeleteByAgentConfigId(agentConfigId) } returns Unit

            buildRepo(delegate, nsRepo).softDeleteByAgentConfigId(agentConfigId)

            verify(exactly = 1) { delegate.softDeleteByAgentConfigId(agentConfigId) }
        }
    })

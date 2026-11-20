package io.whozoss.agentos.configFile

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.integrationConfig.FilesystemIntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import io.whozoss.agentos.plugin.filesystem.filesystemEntityId
import io.whozoss.agentos.prompt.FilesystemPromptRepository
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptRepository
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.skill.FilesystemSkillRepository
import io.whozoss.agentos.skill.Skill
import io.whozoss.agentos.skill.SkillRepository
import io.whozoss.agentos.skill.SkillService
import io.whozoss.agentos.testutil.yamlExportMapper
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * [ConfigFileService] over the real filesystem repositories, reading a temporary `configPath`, with
 * the Neo4j layer mocked: what the file says, what the database copy says, and how they compare.
 */
class ConfigFileServiceUnitSpec :
    StringSpec({
        val namespaceId = UUID.randomUUID()

        lateinit var root: Path
        lateinit var agents: AgentConfigRepository
        lateinit var integrations: IntegrationConfigRepository
        lateinit var skills: SkillRepository
        lateinit var prompts: PromptRepository
        lateinit var service: ConfigFileService
        lateinit var agentService: AgentConfigService
        lateinit var integrationService: IntegrationConfigService
        lateinit var skillService: SkillService
        lateinit var promptService: PromptService
        lateinit var promptLinks: PromptRepository
        lateinit var schedulers: ScheduledPromptRepository

        fun write(
            relativePath: String,
            content: String,
        ) {
            val file = root.resolve(relativePath)
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
        }

        fun persisted() = EntityMetadata(id = UUID.randomUUID(), version = 1)

        beforeTest {
            root = Files.createTempDirectory("config-file-service")
            val namespace = Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = root.toString())
            val namespaceRepository =
                mockk<NamespaceRepository> {
                    every { findByIds(listOf(namespaceId)) } returns listOf(namespace)
                    every { findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns listOf(namespace)
                }
            agents = mockk<AgentConfigRepository>()
            integrations = mockk<IntegrationConfigRepository>()
            skills = mockk<SkillRepository>()
            prompts = mockk<PromptRepository>()
            every { agents.findByParent(namespaceId, true) } returns emptyList()
            every { integrations.findByNamespaceId(namespaceId) } returns emptyList()
            every { skills.findByNamespaceId(namespaceId) } returns emptyList()
            every { prompts.findByParent(namespaceId) } returns emptyList()
            every { agents.findByIds(any(), any()) } returns emptyList()
            every { integrations.findByIds(any(), any()) } returns emptyList()
            every { skills.findByIds(any(), any()) } returns emptyList()
            every { prompts.findByIds(any(), any()) } returns emptyList()
            every { prompts.countActiveByAgentConfigId(any()) } returns 0
            schedulers = mockk { every { countActiveByAgentConfigId(any()) } returns 0 }
            every { schedulers.existsActiveByPromptTemplateId(any()) } returns false
            agentService = mockk { every { delete(any()) } returns true }
            integrationService = mockk { every { delete(any()) } returns true }
            skillService = mockk { every { delete(any()) } returns true }
            promptService = mockk { every { delete(any()) } returns true }
            val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
            promptLinks = FilesystemPromptRepository(prompts, namespaceRepository)
            service =
                ConfigFileService(
                    agentConfigRepository = FilesystemAgentConfigRepository(agents, namespaceRepository),
                    integrationConfigRepository = FilesystemIntegrationConfigRepository(integrations, namespaceRepository),
                    skillRepository = FilesystemSkillRepository(skills, namespaceRepository, yamlMapper),
                    promptRepository = promptLinks,
                    agentConfigService = agentService,
                    integrationConfigService = integrationService,
                    skillService = skillService,
                    promptService = promptService,
                    scheduledPromptRepository = schedulers,
                    namespaceRepository = namespaceRepository,
                    yamlMapper = yamlExportMapper(),
                )
        }

        val agentFile =
            """
            name: coday
            description: The default agent
            modelName: BIG
            executionMode: ADVANCED
            integrations:
              FILES:
              JIRA:
                - GetIssue
            docs:
              - ../docs/guide.md
              - ../docs/
            """.trimIndent()

        /** The database copy a super-admin gets by saving the file agent unchanged from the form. */
        fun agentCopy(instructions: String? = null) =
            AgentConfig(
                metadata = persisted(),
                namespaceId = namespaceId,
                name = "coday",
                description = "The default agent",
                instructions = instructions,
                modelName = "BIG",
                executionMode = ExecutionMode.ADVANCED,
                integrations = mapOf("FILES" to null, "JIRA" to listOf("GetIssue")),
                docs =
                    listOf(
                        root.resolve("docs/guide.md").toAbsolutePath().normalize().toString(),
                        root.resolve("docs").toAbsolutePath().normalize().toString() + "/",
                    ),
            )

        "a file that no database config masks is FILE, under the file's own id" {
            write("agents/coday.yaml", agentFile)

            service.list(namespaceId) shouldContainExactly
                listOf(
                    ConfigFileDto(
                        kind = FilesystemEntityKind.AGENT,
                        name = "coday",
                        path = "agents/coday.yaml",
                        state = ConfigFileState.FILE,
                        configId = filesystemEntityId(FilesystemEntityKind.AGENT, namespaceId, "coday"),
                    ),
                )
        }

        "a database copy that only repeats its file is OVERRIDDEN but not modified" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy()
            every { agents.findByParent(namespaceId, true) } returns listOf(copy)

            val status = service.list(namespaceId).single()

            status.state shouldBe ConfigFileState.OVERRIDDEN
            status.configId shouldBe copy.metadata.id
            status.modified shouldBe false
        }

        "a database copy that differs from its file is modified, and the diff shows both sides in the file format" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy(instructions = "Answer in French.")
            every { agents.findByParent(namespaceId, true) } returns listOf(copy)
            every { agents.findByIds(listOf(copy.metadata.id), false) } returns listOf(copy)

            service.list(namespaceId).single().modified shouldBe true
            val diff = service.diff(FilesystemEntityKind.AGENT, copy.metadata.id)

            diff.path shouldBe "agents/coday.yaml"
            diff.modified shouldBe true
            val entry = diff.files.single()
            entry.path shouldBe "agents/coday.yaml"
            entry.fileContent!! shouldNotContain "instructions"
            entry.configContent!! shouldContain "Answer in French."
            entry.configContent!! shouldContain "../docs/guide.md"
            entry.configContent!! shouldNotContain root.toString()
        }

        "an agent with a loop payload is noted as one its file cannot hold" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy().copy(loopConfig = JsonNodeFactory.instance.objectNode().put("steps", 3))
            every { agents.findByParent(namespaceId, true) } returns listOf(copy)
            every { agents.findByIds(listOf(copy.metadata.id), false) } returns listOf(copy)

            service.diff(FilesystemEntityKind.AGENT, copy.metadata.id).notes.single() shouldContain "loopConfig"
        }

        "an integration path written with the configPath token matches the same absolute path in the database" {
            write(
                "integrations/repo.yaml",
                """
                name: repo
                integrationType: FILE_ACCESS
                parameters:
                  rootPath: "{{NAMESPACE_CONFIG_PATH}}/../code"
                """.trimIndent(),
            )
            val copy =
                IntegrationConfig(
                    metadata = persisted(),
                    namespaceId = namespaceId,
                    name = "repo",
                    integrationType = "FILE_ACCESS",
                    parameters = JsonNodeFactory.instance.objectNode().put("rootPath", "$root/../code"),
                )
            every { integrations.findByNamespaceId(namespaceId) } returns listOf(copy)
            every { integrations.findByIds(listOf(copy.metadata.id), false) } returns listOf(copy)

            service.list(namespaceId).single().modified shouldBe false
            service.diff(FilesystemEntityKind.INTEGRATION, copy.metadata.id).files.single().configContent!! shouldContain
                "{{NAMESPACE_CONFIG_PATH}}/../code"
        }

        "a personal integration never overrides a file" {
            write("integrations/repo.yaml", "name: repo\nintegrationType: FILE_ACCESS\n")
            val personal =
                IntegrationConfig(
                    metadata = persisted(),
                    namespaceId = namespaceId,
                    userId = UUID.randomUUID(),
                    name = "repo",
                    integrationType = "FILE_ACCESS",
                )
            every { integrations.findByIds(listOf(personal.metadata.id), false) } returns listOf(personal)

            service.list(namespaceId).single().state shouldBe ConfigFileState.FILE
            shouldThrow<ResourceNotFoundException> { service.diff(FilesystemEntityKind.INTEGRATION, personal.metadata.id) }
        }

        "a skill is compared file by file, resources included" {
            write("skills/review/SKILL.md", "---\nname: review\ndescription: Review code\n---\n\nRead the diff.\n")
            write("skills/review/checklist.md", "- tests\n")
            val copy =
                Skill(
                    metadata = persisted(),
                    namespaceId = namespaceId,
                    name = "review",
                    description = "Review code",
                    body = "Read the diff.\n",
                    resources = mapOf("template.md" to "# Review\n"),
                )
            every { skills.findByNamespaceId(namespaceId) } returns listOf(copy)
            every { skills.findByIds(listOf(copy.metadata.id), false) } returns listOf(copy)

            val diff = service.diff(FilesystemEntityKind.SKILL, copy.metadata.id)

            diff.modified shouldBe true
            diff.files.map { it.path } shouldBe
                listOf("skills/review/SKILL.md", "skills/review/checklist.md", "skills/review/template.md")
            diff.files[0].fileContent shouldBe diff.files[0].configContent
            diff.files[1].configContent.shouldBeNull()
            diff.files[2].fileContent.shouldBeNull()
        }

        "a prompt copy that only adds what a file cannot hold is not modified, and says what the file would lose" {
            write("prompts/plan.yaml", "name: plan\ncontent:\n  - \"Plan {{ARGUMENTS}}\"\n")
            val copy =
                Prompt(
                    metadata = persisted(),
                    namespaceId = namespaceId,
                    agentConfigId = UUID.randomUUID(),
                    name = "plan",
                    content = listOf("Plan {{ARGUMENTS}}"),
                )
            every { prompts.findByParent(namespaceId) } returns listOf(copy)
            every { prompts.findByIds(listOf(copy.metadata.id), false) } returns listOf(copy)

            service.list(namespaceId).single().modified shouldBe false
            service.diff(FilesystemEntityKind.PROMPT, copy.metadata.id).notes.single() shouldContain "@agentName"
        }

        "a database config that masks no file has no diff" {
            write("agents/coday.yaml", agentFile)
            val other = agentCopy().copy(name = "other")
            every { agents.findByParent(namespaceId, true) } returns listOf(other)
            every { agents.findByIds(listOf(other.metadata.id), false) } returns listOf(other)

            service.list(namespaceId).single().state shouldBe ConfigFileState.FILE
            shouldThrow<ResourceNotFoundException> { service.diff(FilesystemEntityKind.AGENT, other.metadata.id) }
        }

        "the four kinds are listed together, agents first" {
            write("agents/coday.yaml", agentFile)
            write("integrations/repo.yaml", "name: repo\nintegrationType: FILE_ACCESS\n")
            write("skills/review/SKILL.md", "---\nname: review\ndescription: Review code\n---\n\nRead the diff.\n")
            write("prompts/plan.yaml", "name: plan\ncontent:\n  - Plan\n")

            service.list(namespaceId).map { it.kind to it.path } shouldBe
                listOf(
                    FilesystemEntityKind.AGENT to "agents/coday.yaml",
                    FilesystemEntityKind.INTEGRATION to "integrations/repo.yaml",
                    FilesystemEntityKind.SKILL to "skills/review/SKILL.md",
                    FilesystemEntityKind.PROMPT to "prompts/plan.yaml",
                )
        }

        "a namespace without configPath has no config file" {
            val other = UUID.randomUUID()
            val bare =
                FilesystemAgentConfigRepository(
                    agents,
                    mockk { every { findByIds(listOf(other)) } returns listOf(Namespace(metadata = EntityMetadata(id = other), name = "bare")) },
                )

            bare.configFiles(other).shouldBeEmpty()
        }

        // ------------------------------------------------------------------ write back, download, discard

        fun stubAgentCopy(copy: AgentConfig) {
            every { agents.findByParent(namespaceId, true) } returns listOf(copy)
            every { agents.findByIds(listOf(copy.metadata.id), false) } returns listOf(copy)
        }

        "writing a copy to its file replaces the file with it, deletes the copy, and the file then says the same" {
            write("agents/coday.yaml", agentFile)
            Files.setPosixFilePermissions(root.resolve("agents/coday.yaml"), PosixFilePermissions.fromString("rw-rw-r--"))
            val copy = agentCopy(instructions = "Answer in French.")
            stubAgentCopy(copy)
            val hash = service.diff(FilesystemEntityKind.AGENT, copy.metadata.id).fileHash

            val result = service.writeToFile(FilesystemEntityKind.AGENT, copy.metadata.id, hash)

            result shouldBe ConfigFileWriteResultDto(path = "agents/coday.yaml", written = true, copyDeleted = true)
            Files.readString(root.resolve("agents/coday.yaml")) shouldContain "Answer in French."
            // The file keeps its permissions, not those of the private temporary file written first.
            PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("agents/coday.yaml"))) shouldBe "rw-rw-r--"
            Files.list(root.resolve("agents")).use { it.toList() }.map { it.fileName.toString() } shouldBe listOf("coday.yaml")
            verify(exactly = 1) { agentService.delete(copy.metadata.id) }
            // Read again at once, not after the cache's 5 minutes: the file now holds exactly the copy.
            service.list(namespaceId).single().modified shouldBe false
        }

        "a copy that only repeats its file is not written again, only deleted" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy()
            stubAgentCopy(copy)
            val hash = service.diff(FilesystemEntityKind.AGENT, copy.metadata.id).fileHash

            service.writeToFile(FilesystemEntityKind.AGENT, copy.metadata.id, hash).written shouldBe false

            Files.readString(root.resolve("agents/coday.yaml")) shouldBe agentFile
            verify(exactly = 1) { agentService.delete(copy.metadata.id) }
        }

        "a file changed since the differences were shown is not overwritten" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy(instructions = "Answer in French.")
            stubAgentCopy(copy)
            val hash = service.diff(FilesystemEntityKind.AGENT, copy.metadata.id).fileHash
            write("agents/coday.yaml", "$agentFile\n# edited by a git pull\n")

            shouldThrow<ConflictException> { service.writeToFile(FilesystemEntityKind.AGENT, copy.metadata.id, hash) }

            Files.readString(root.resolve("agents/coday.yaml")) shouldContain "edited by a git pull"
            verify(exactly = 0) { agentService.delete(any()) }
        }

        "what a file cannot hold refuses the write and keeps the copy" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy().copy(loopConfig = JsonNodeFactory.instance.objectNode().put("steps", 3))
            stubAgentCopy(copy)
            val hash = service.diff(FilesystemEntityKind.AGENT, copy.metadata.id).fileHash

            shouldThrow<UnprocessableEntityException> { service.writeToFile(FilesystemEntityKind.AGENT, copy.metadata.id, hash) }

            Files.readString(root.resolve("agents/coday.yaml")) shouldBe agentFile
            verify(exactly = 0) { agentService.delete(any()) }
        }

        "an agent with prompts attached is neither written nor discarded: deleting it would delete them" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy(instructions = "Answer in French.")
            stubAgentCopy(copy)
            every { prompts.countActiveByAgentConfigId(copy.metadata.id) } returns 2
            val diff = service.diff(FilesystemEntityKind.AGENT, copy.metadata.id)

            diff.cannotWrite.single() shouldContain "2 prompts are attached"
            diff.cannotDiscard shouldBe diff.cannotWrite
            shouldThrow<UnprocessableEntityException> { service.writeToFile(FilesystemEntityKind.AGENT, copy.metadata.id, diff.fileHash) }
            shouldThrow<UnprocessableEntityException> { service.discard(FilesystemEntityKind.AGENT, copy.metadata.id) }
            verify(exactly = 0) { agentService.delete(any()) }
        }

        "an integration holding a credential in clear is not written to a file meant to be committed" {
            write("integrations/jira.yaml", "name: jira\nintegrationType: MCP_HTTP\n")
            val copy =
                IntegrationConfig(
                    metadata = persisted(),
                    namespaceId = namespaceId,
                    name = "jira",
                    integrationType = "MCP_HTTP",
                    parameters = JsonNodeFactory.instance.objectNode().put("url", "https://jira").put("token", "s3cr3t"),
                )
            every { integrations.findByNamespaceId(namespaceId) } returns listOf(copy)
            every { integrations.findByIds(listOf(copy.metadata.id), false) } returns listOf(copy)
            val diff = service.diff(FilesystemEntityKind.INTEGRATION, copy.metadata.id)

            diff.cannotWrite.single() shouldContain "token"
            diff.cannotDiscard.shouldBeEmpty()
            shouldThrow<UnprocessableEntityException> {
                service.writeToFile(FilesystemEntityKind.INTEGRATION, copy.metadata.id, diff.fileHash)
            }
            Files.readString(root.resolve("integrations/jira.yaml")) shouldNotContain "s3cr3t"
        }

        "a skill is written as its folder: resources added, resources it dropped removed" {
            write("skills/review/SKILL.md", "---\nname: review\ndescription: Review code\n---\n\nRead the diff.\n")
            write("skills/review/checklist.md", "- tests\n")
            val copy =
                Skill(
                    metadata = persisted(),
                    namespaceId = namespaceId,
                    name = "review",
                    description = "Review code",
                    body = "Read the diff, then comment.\n",
                    resources = mapOf("template.md" to "# Review\n"),
                )
            every { skills.findByNamespaceId(namespaceId) } returns listOf(copy)
            every { skills.findByIds(listOf(copy.metadata.id), false) } returns listOf(copy)
            val hash = service.diff(FilesystemEntityKind.SKILL, copy.metadata.id).fileHash

            service.writeToFile(FilesystemEntityKind.SKILL, copy.metadata.id, hash).copyDeleted shouldBe true

            Files.readString(root.resolve("skills/review/SKILL.md")) shouldContain "Read the diff, then comment."
            Files.readString(root.resolve("skills/review/template.md")) shouldBe "# Review\n"
            PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("skills/review/template.md"))) shouldBe "rw-r--r--"
            Files.exists(root.resolve("skills/review/checklist.md")) shouldBe false
            service.list(namespaceId).single().modified shouldBe false
        }

        "a folder AgentOS cannot write in leaves the file and the copy as they were, and says why" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy(instructions = "Answer in French.")
            stubAgentCopy(copy)
            val hash = service.diff(FilesystemEntityKind.AGENT, copy.metadata.id).fileHash
            val folder = root.resolve("agents").toFile()
            folder.setWritable(false)
            try {
                val error = shouldThrow<ConfigFileWriteException> { service.writeToFile(FilesystemEntityKind.AGENT, copy.metadata.id, hash) }
                error.message shouldBe "AgentOS is not allowed to write in agents of this namespace's config folder."
            } finally {
                folder.setWritable(true)
            }
            Files.readString(root.resolve("agents/coday.yaml")) shouldBe agentFile
            verify(exactly = 0) { agentService.delete(any()) }
        }

        "a copy that cannot be deleted after the write is reported, the file being written" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy(instructions = "Answer in French.")
            stubAgentCopy(copy)
            every { agentService.delete(copy.metadata.id) } throws IllegalStateException("boom")
            val hash = service.diff(FilesystemEntityKind.AGENT, copy.metadata.id).fileHash

            val result = service.writeToFile(FilesystemEntityKind.AGENT, copy.metadata.id, hash)

            result.written shouldBe true
            result.copyDeleted shouldBe false
            result.message!! shouldContain "The file is written"
        }

        "discarding a copy deletes it and leaves the file alone" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy(instructions = "Answer in French.")
            stubAgentCopy(copy)

            service.discard(FilesystemEntityKind.AGENT, copy.metadata.id) shouldBe
                ConfigFileWriteResultDto(path = "agents/coday.yaml", written = false, copyDeleted = true)

            Files.readString(root.resolve("agents/coday.yaml")) shouldBe agentFile
            verify(exactly = 1) { agentService.delete(copy.metadata.id) }
        }

        "the download is the file the write would write: one file, or a zip of a skill's folder" {
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy(instructions = "Answer in French.")
            stubAgentCopy(copy)
            write("skills/review/SKILL.md", "---\nname: review\ndescription: Review code\n---\n\nRead the diff.\n")
            val skill =
                Skill(
                    metadata = persisted(),
                    namespaceId = namespaceId,
                    name = "review",
                    description = "Review code",
                    body = "Read the diff.\n",
                    resources = mapOf("template.md" to "# Review\n"),
                )
            every { skills.findByNamespaceId(namespaceId) } returns listOf(skill)
            every { skills.findByIds(listOf(skill.metadata.id), false) } returns listOf(skill)

            val agentDownload = service.download(FilesystemEntityKind.AGENT, copy.metadata.id)
            agentDownload.fileName shouldBe "coday.yaml"
            String(agentDownload.content) shouldBe
                service.diff(FilesystemEntityKind.AGENT, copy.metadata.id).files.single().configContent

            val skillDownload = service.download(FilesystemEntityKind.SKILL, skill.metadata.id)
            skillDownload.fileName shouldBe "review.zip"
            ZipInputStream(ByteArrayInputStream(skillDownload.content)).use { zip ->
                generateSequence { zip.nextEntry?.name }.toList()
            } shouldBe listOf("review/SKILL.md", "review/template.md")
        }

        "the diff names the other namespaces that read the same folder" {
            val other = Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "staging", configPath = root.toString())
            val namespaceRepository =
                mockk<NamespaceRepository> {
                    every { findByIds(listOf(namespaceId)) } returns
                        listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = root.toString()))
                    every { findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY) } returns
                        listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", configPath = root.toString()), other)
                }
            write("agents/coday.yaml", agentFile)
            val copy = agentCopy(instructions = "Answer in French.")
            stubAgentCopy(copy)
            val shared =
                ConfigFileService(
                    agentConfigRepository = FilesystemAgentConfigRepository(agents, namespaceRepository),
                    integrationConfigRepository = FilesystemIntegrationConfigRepository(integrations, namespaceRepository),
                    skillRepository = mockk(relaxed = true),
                    promptRepository = promptLinks,
                    agentConfigService = agentService,
                    integrationConfigService = integrationService,
                    skillService = skillService,
                    promptService = promptService,
                    scheduledPromptRepository = schedulers,
                    namespaceRepository = namespaceRepository,
                    yamlMapper = yamlExportMapper(),
                )

            shared.diff(FilesystemEntityKind.AGENT, copy.metadata.id).alsoReadBy shouldBe listOf("staging")
        }
    })


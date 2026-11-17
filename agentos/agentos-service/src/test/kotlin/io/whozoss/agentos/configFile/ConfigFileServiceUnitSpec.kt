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
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.integrationConfig.FilesystemIntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import io.whozoss.agentos.plugin.filesystem.filesystemEntityId
import io.whozoss.agentos.prompt.FilesystemPromptRepository
import io.whozoss.agentos.prompt.Prompt
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.skill.FilesystemSkillRepository
import io.whozoss.agentos.skill.Skill
import io.whozoss.agentos.skill.SkillRepository
import io.whozoss.agentos.testutil.yamlExportMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

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
            val yamlMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())
            service =
                ConfigFileService(
                    agentConfigRepository = FilesystemAgentConfigRepository(agents, namespaceRepository),
                    integrationConfigRepository = FilesystemIntegrationConfigRepository(integrations, namespaceRepository),
                    skillRepository = FilesystemSkillRepository(skills, namespaceRepository, yamlMapper),
                    promptRepository = FilesystemPromptRepository(prompts, namespaceRepository),
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
    })

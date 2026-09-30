package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.exchange.ExchangeStorageConfigProperties
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.permissions.Action
import java.nio.file.Files
import java.util.UUID

class GitToolsRunIntegrationSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val root = Case(namespaceId = namespaceId, title = "Root")
        val child = Case(namespaceId = namespaceId, title = "Child", parentCaseId = root.id)
        val ordinary = Case(namespaceId = namespaceId, title = "Ordinary")
        val storage =
            ExchangeStorageService(
                ExchangeStorageConfigProperties(mountRoot = Files.createTempDirectory("git-tools-run-").toString()),
                listOf(GitMetadataEntries()),
            )
        val repository =
            mockk<CaseRepository> {
                every { findByIds(any(), any()) } answers { firstArg<Collection<UUID>>().mapNotNull { listOf(root, child, ordinary).find { case -> case.id == it } } }
            }
        val bindings = InMemoryCaseResourceBindingService()
        val capabilities = mockk<ExchangeCapabilityService>()
        val integration = GitToolsRunIntegration(GitExchangeRootResolver(repository, bindings, storage), capabilities, storage, mapper)
        val settings =
            GitRepositorySettings(
                configId = UUID.randomUUID(),
                namespaceId = namespaceId,
                repositoryUrl = "https://forge.example/org/project.git",
                mainBranch = "develop",
                serviceAuthSettingId = UUID.randomUUID(),
                autoWorktreeForRootCases = true,
                setupCommand = null,
            )
        val binding =
            bindings.create(
                CaseResourceBinding(
                    rootCaseId = root.id,
                    namespaceId = namespaceId,
                    integrationConfigId = settings.configId,
                    status = CaseResourceStatus.READY,
                    settingsJson = mapper.writeValueAsString(settings),
                ),
            )

        fun config(
            type: String,
            parameters: String = "{}",
        ) = IntegrationConfig(
            namespaceId = namespaceId,
            userId = null,
            name = type.lowercase(),
            integrationType = type,
            authSettingName = "github",
            parameters = mapper.readTree(parameters),
        )

        fun context(caseId: UUID?) = AgentExecutionContext(namespaceId = namespaceId, caseId = caseId, userId = userId)

        fun mayWrite(allowed: Boolean) {
            every { capabilities.canAccessCase(userId.toString(), any(), any(), Action.WRITE) } returns allowed
        }

        "Git tools receive the family worktree and the Git context recorded when it was equipped" {
            mayWrite(true)
            val saved = config("GIT", """{"workingDirectory":"/elsewhere","gitDir":"/elsewhere/.git","repositoryUrl":"https://other.example/x.git"}""")

            val effective = integration.customize(listOf(saved), context(child.id)).single()

            val common = storage.namespaceGitDirectory(namespaceId).toAbsolutePath().normalize()
            val expected =
                mapOf(
                    "workingDirectory" to storage.caseRoot(namespaceId, root.id, root.metadata.created).resolve("repo").toAbsolutePath().normalize().toString(),
                    "gitDir" to common.resolve("worktrees").resolve(root.id.toString()).toString(),
                    "commonGitDir" to common.toString(),
                    "repositoryUrl" to "https://forge.example/org/project.git",
                    "mainBranch" to "develop",
                )
            expected.forEach { (key, value) -> effective.parameters!![key].asText() shouldBe value }
            effective.authSettingName shouldBe "github"
            saved.parameters!!["gitDir"].asText() shouldBe "/elsewhere/.git"
        }

        "Git tools exist only inside a Git workspace the user may write, other integrations are untouched" {
            val bash = config("BASH", """{"workingDirectory":"/srv/project"}""")
            val git = listOf(config("GIT"), bash)

            mayWrite(true)
            integration.customize(git, context(null)) shouldBe listOf(bash)
            integration.customize(git, context(ordinary.id)) shouldBe listOf(bash)
            mayWrite(false)
            integration.customize(git, context(child.id)) shouldBe listOf(bash)
            mayWrite(true)
            bindings.update(binding.copy(settingsJson = null))
            integration.customize(git, context(child.id)) shouldBe listOf(bash)
            bindings.update(binding)
        }

        "without Git workspaces a saved GIT integration never reaches the plugin" {
            val bash = config("BASH")
            val saved = config("GIT", """{"workingDirectory":"/elsewhere","gitDir":"/elsewhere/.git"}""")

            GitToolsRunIntegration(null, capabilities, storage, mapper).customize(listOf(saved, bash), context(child.id)) shouldBe listOf(bash)
        }
    })

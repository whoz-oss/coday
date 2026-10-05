package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        val integration = GitToolsRunIntegration(GitExchangeRootResolver(repository, bindings, storage), capabilities, storage)
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
                    settings = settings,
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

        /** The run's own case asks to write the family's shared directory, which the root owns. */
        fun mayWrite(allowed: Boolean) {
            every { capabilities.canAccessCase(userId.toString(), child.id, match { it.ownerCaseId == root.id }, Action.WRITE) } returns
                allowed
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
            verify { capabilities.canAccessCase(userId.toString(), child.id, match { it.ownerCaseId == root.id }, Action.WRITE) }
        }

        "in an equipped family Git tools exist only for a user who may write the workspace" {
            val bash = config("BASH", """{"workingDirectory":"/srv/project"}""")
            val git = listOf(config("GIT"), bash)

            mayWrite(false)
            integration.customize(git, context(child.id)) shouldBe listOf(bash)
            mayWrite(true)
            val withoutSettings = bindings.update(binding.copy(settings = null))
            try {
                integration.customize(git, context(child.id)) shouldBe listOf(bash)
            } finally {
                bindings.update(withoutSettings.copy(settings = binding.settings))
            }
        }

        "outside an equipped family a GIT integration reaches the run as configured" {
            val bash = config("BASH", """{"workingDirectory":"/srv/project"}""")
            val git = listOf(config("GIT", """{"workingDirectory":"/srv/repository"}"""), bash)

            mayWrite(true)
            integration.customize(git, context(null)) shouldBe git
            integration.customize(git, context(ordinary.id)) shouldBe git
        }
    })

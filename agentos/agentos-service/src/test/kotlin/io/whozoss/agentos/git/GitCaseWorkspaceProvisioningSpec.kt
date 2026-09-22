package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.git.core.GitRemoteUrlValidator
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * When a case creation results in a workspace being requested.
 *
 * The rules are all about *not* acting: a sub-case never allocates, an unassociated namespace
 * never allocates, an association with automation off never allocates, and a broken association
 * is rejected instead of silently creating an unequipped family.
 */
class GitCaseWorkspaceProvisioningSpec :
    StringSpec({

        val namespaceId = UUID.randomUUID()

        fun settings(autoWorktree: Boolean): GitRepositorySettings =
            GitRepositorySettings(
                configId = UUID.randomUUID(),
                namespaceId = namespaceId,
                repositoryUrl = "https://forge.example/org/project.git",
                mainBranch = "main",
                serviceAuthSettingId = UUID.randomUUID(),
                autoWorktreeForRootCases = autoWorktree,
                setupCommand = null,
            )

        fun availability(available: Boolean = true): GitAvailability = mockk { every { isAvailable() } returns available }

        fun hook(
            bindings: CaseResourceBindingService,
            available: Boolean = true,
            resolve: () -> GitRepositorySettings?,
        ): GitCaseWorkspaceProvisioning {
            val association = mockk<GitRepositoryAssociationService> { every { findAutomaticSettings(any()) } answers { resolve() } }
            return GitCaseWorkspaceProvisioning(
                association,
                bindings,
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper(),
                availability(available),
            )
        }

        fun rootCase(title: String = "Corriger les exports"): Case =
            Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = title)

        fun subCase(parent: Case): Case =
            Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = "Developpement", parentCaseId = parent.id)

        "a root case is not equipped while the GIT plugin is not loaded" {
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()

            hook(bindings, available = false) { settings(autoWorktree = true) }.onCaseCreated(case)

            bindings.findByRootCaseId(case.id).shouldBeNull()
        }

        "a root case in a namespace with automation on is equipped" {
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()

            hook(bindings) { settings(autoWorktree = true) }.onCaseCreated(case)

            val binding = bindings.findByRootCaseId(case.id)
            binding.shouldNotBeNull()
            binding.status shouldBe CaseResourceStatus.REQUESTED
        }

        "a sub-case never allocates: it shares its root's workspace" {
            val bindings = InMemoryCaseResourceBindingService()
            val root = rootCase()
            val child = subCase(root)

            hook(bindings) { settings(autoWorktree = true) }.onCaseCreated(child)

            bindings.findByRootCaseId(child.id).shouldBeNull()
        }

        "a namespace without an association equips nothing" {
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()

            hook(bindings) { null }.onCaseCreated(case)

            bindings.findByRootCaseId(case.id).shouldBeNull()
        }

        "an association with automation off equips nothing" {
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()

            hook(bindings) { settings(autoWorktree = false) }.onCaseCreated(case)

            bindings.findByRootCaseId(case.id).shouldBeNull()
        }

        "a broken association is rejected before creating an unequipped family" {
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()

            shouldThrow<BadRequestException> {
                hook(bindings) { throw BadRequestException("serviceAuthSettingId must be a UUID") }.onCaseCreated(case)
            }

            bindings.findByRootCaseId(case.id).shouldBeNull()
        }

        "allocation is idempotent for the same root case" {
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()
            val provisioning = hook(bindings) { settings(autoWorktree = true) }

            provisioning.onCaseCreated(case)
            provisioning.onCaseCreated(case)

            bindings.findByParent(namespaceId).size shouldBe 1
        }
        "disabled automation never parses broken Git fields or resolves a remote host" {
            val config = io.whozoss.agentos.integrationConfig.IntegrationConfig(
                namespaceId = namespaceId, name = "git", integrationType = GitRepositoryIntegration.TYPE,
                parameters = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(
                    """{"autoWorktreeForRootCases":false,"repositoryUrl":"https://127.0.0.1/repo","serviceAuthSettingId":"invalid"}"""),
            )
            val configs = mockk<io.whozoss.agentos.integrationConfig.IntegrationConfigService> {
                every { findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE) } returns config
            }
            val validator = mockk<GitRemoteUrlValidator>()
            val association = GitRepositoryAssociationService(configs, GitRepositorySettingsFactory(validator))
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()
            GitCaseWorkspaceProvisioning(association, bindings, com.fasterxml.jackson.module.kotlin.jacksonObjectMapper(), availability())
                .onCaseCreated(case)
            bindings.findByRootCaseId(case.id).shouldBeNull()
            io.mockk.verify(exactly = 0) { validator.validate(any()) }
        }

        "enabled automation records preparation without doing a DNS check in case creation" {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val config = io.whozoss.agentos.integrationConfig.IntegrationConfig(
                namespaceId = namespaceId, name = "git", integrationType = GitRepositoryIntegration.TYPE,
                parameters = mapper.valueToTree(mapOf(
                    GitRepositoryIntegration.PARAM_AUTO_WORKTREE to true,
                    GitRepositoryIntegration.PARAM_REPOSITORY_URL to "https://127.0.0.1/repo",
                    GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID to UUID.randomUUID().toString(),
                )),
            )
            val configs = mockk<io.whozoss.agentos.integrationConfig.IntegrationConfigService> {
                every { findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE) } returns config
            }
            val validator = mockk<GitRemoteUrlValidator>()
            val association = GitRepositoryAssociationService(configs, GitRepositorySettingsFactory(validator))
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()
            GitCaseWorkspaceProvisioning(association, bindings, mapper, availability()).onCaseCreated(case)
            bindings.findByRootCaseId(case.id)?.status shouldBe CaseResourceStatus.REQUESTED
            io.mockk.verify(exactly = 0) { validator.validate(any()) }
        }

    })

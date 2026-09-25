package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitRemoteUrlValidator
import io.whozoss.agentos.integrationConfig.InMemoryIntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

/**
 * A `GIT_REPOSITORY` configuration is validated when it is **saved**, not when it is later read.
 *
 * Without this the generic CRUD stores any JSON in `parameters` and answers 201, so a malformed
 * association only fails much later, during provisioning, far from the mistake.
 */
class GitRepositoryConfigPolicySpec :
    StringSpec({

        val objectMapper = ObjectMapper()
        val checkouts = mockk<RepositoryCheckoutService>()
        val provisioner = mockk<RepositoryCheckoutProvisioner>(relaxed = true)
        val availability = mockk<GitAvailability>()
        beforeTest {
            clearMocks(provisioner)
            every { checkouts.findByNamespaceId(any()) } returns null
            every { availability.isAvailable() } returns true
        }
        val policy = GitRepositoryConfigPolicy(
            GitRepositorySettingsFactory(GitRemoteUrlValidator(GitExecutionProperties())),
            checkouts,
            provisioner,
            availability,
        )

        fun newService(): IntegrationConfigServiceImpl =
            IntegrationConfigServiceImpl(
                InMemoryIntegrationConfigRepository(),
                IntegrationConfigMergeStrategy(),
                listOf(policy),
            )

        val namespaceId = UUID.randomUUID()
        val authSettingId = UUID.randomUUID()

        fun config(
            parameters: Map<String, Any?>,
            name: String = "project-repository",
            integrationType: String = GitRepositoryIntegration.TYPE,
        ): IntegrationConfig =
            IntegrationConfig(
                namespaceId = namespaceId,
                name = name,
                integrationType = integrationType,
                parameters = objectMapper.valueToTree(parameters),
            )

        fun validParameters(): MutableMap<String, Any?> =
            mutableMapOf(
                GitRepositoryIntegration.PARAM_REPOSITORY_URL to "https://forge.example/org/project.git",
                GitRepositoryIntegration.PARAM_MAIN_BRANCH to "main",
                GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID to authSettingId.toString(),
            )

        "a valid association is saved" {
            val saved = newService().create(config(validParameters()))

            saved.id.shouldNotBeNull()
        }

        "an association cannot be saved while the GIT plugin is not loaded" {
            every { availability.isAvailable() } returns false

            val error = shouldThrow<BadRequestException> { newService().create(config(validParameters())) }

            error.message shouldContain "GIT plugin"
            verify(exactly = 0) { provisioner.requestPreparation(any()) }
        }

        "generic create and update queue preparation from the saved namespace configuration" {
            val service = newService()
            val saved = service.create(config(validParameters()))
            val replacementAccount = UUID.randomUUID()
            val updated = saved.copy(parameters = objectMapper.valueToTree(validParameters().also {
                it[GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID] = replacementAccount.toString()
            }))
            service.update(updated)
            verify(exactly = 1) { provisioner.requestPreparation(match {
                it.configId == saved.id && it.namespaceId == namespaceId && it.serviceAuthSettingId == authSettingId
            }) }
            verify(exactly = 1) { provisioner.requestPreparation(match {
                it.configId == saved.id && it.namespaceId == namespaceId && it.serviceAuthSettingId == replacementAccount
            }) }
        }

        "dedicated namespace settings and generic config CRUD persist the same preparation intent" {
            val checkoutStore = InMemoryRepositoryCheckoutService()
            val factory = GitRepositorySettingsFactory(GitRemoteUrlValidator(GitExecutionProperties()))
            val actualProvisioner = RepositoryCheckoutProvisioner(mockk(), GitExecutionProperties(), mockk(), checkoutStore, mockk())
            val service = IntegrationConfigServiceImpl(InMemoryIntegrationConfigRepository(), IntegrationConfigMergeStrategy(),
                listOf(GitRepositoryConfigPolicy(factory, checkoutStore, actualProvisioner, availability)))
            val dedicatedNamespace = UUID.randomUUID()
            val namespaces = mockk<NamespaceService> {
                every { findById(dedicatedNamespace) } returns Namespace(metadata = EntityMetadata(id = dedicatedNamespace), name = "Test")
            }
            val controller = NamespaceGitController(service, GitRepositoryAssociationService(service, factory), checkoutStore, objectMapper, namespaces, availability)
            val request = NamespaceGitRequest(repositoryUrl = "https://forge.example/org/project.git",
                mainBranch = "main", serviceAuthSettingId = authSettingId)

            val generic = service.create(config(validParameters()))
            val dedicated = controller.setAssociation(dedicatedNamespace, request)
            dedicated.checkoutStatus shouldBe RepositoryCheckoutStatus.PREPARING.name
            for (namespace in listOf(namespaceId, dedicatedNamespace)) {
                val queued = checkoutStore.findByNamespaceId(namespace).shouldNotBeNull()
                val saved = service.findActiveNamespaceSingleton(namespace, GitRepositoryIntegration.TYPE).shouldNotBeNull()
                queued.integrationConfigId shouldBe saved.id
                queued.repositoryUrl shouldBe request.repositoryUrl
                queued.mainBranch shouldBe request.mainBranch
                queued.status shouldBe RepositoryCheckoutStatus.PREPARING
                checkoutStore.markStatus(queued.id, RepositoryCheckoutStatus.FAILED, "temporary failure")
            }
            service.update(generic)
            controller.setAssociation(dedicatedNamespace, request)
            for (namespace in listOf(namespaceId, dedicatedNamespace)) {
                checkoutStore.findByNamespaceId(namespace)!!.status shouldBe RepositoryCheckoutStatus.PREPARING
                checkoutStore.findByParent(namespace).size shouldBe 1
            }
        }

        "persistence failures never queue preparation for create or update" {
            for (updating in listOf(false, true)) {
                val repository = spyk(InMemoryIntegrationConfigRepository())
                val service = IntegrationConfigServiceImpl(repository, IntegrationConfigMergeStrategy(), listOf(policy))
                val value = if (updating) service.create(config(validParameters())) else config(validParameters())
                clearMocks(provisioner, answers = false)
                every { repository.save(any()) } throws DataIntegrityViolationException("storage refused the save")

                shouldThrow<DataIntegrityViolationException> {
                    if (updating) service.update(value) else service.create(value)
                }
                verify(exactly = 0) { provisioner.requestPreparation(any()) }
            }
        }

        "rejected configuration never queues preparation" {
            val parameters = validParameters().also { it[GitRepositoryIntegration.PARAM_MAIN_BRANCH] = "not a branch" }
            shouldThrow<BadRequestException> { newService().create(config(parameters)) }
            verify(exactly = 0) { provisioner.requestPreparation(any()) }
        }

        "queuing failure leaves the saved association available for workspace preparation" {
            val service = newService()
            every { provisioner.requestPreparation(any()) } throws IllegalStateException("queue unavailable")
            val saved = service.create(config(validParameters()))
            service.findById(saved.id)?.parameters shouldBe saved.parameters
        }

        "an ssh URL is refused at save time, not at provisioning time" {
            val parameters =
                validParameters().also {
                    it[GitRepositoryIntegration.PARAM_REPOSITORY_URL] = "ssh://git@forge.example/org/project.git"
                }

            val error = shouldThrow<BadRequestException> { newService().create(config(parameters)) }

            error.message!! shouldContain "scheme"
        }

        "a URL embedding credentials is refused at save time" {
            val parameters =
                validParameters().also {
                    it[GitRepositoryIntegration.PARAM_REPOSITORY_URL] = "https://user:token@forge.example/org/p.git"
                }

            shouldThrow<BadRequestException> { newService().create(config(parameters)) }
        }

        "an auth setting given by name rather than UUID is refused at save time" {
            val parameters =
                validParameters().also {
                    it[GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID] = "git-service-account"
                }

            val error = shouldThrow<BadRequestException> { newService().create(config(parameters)) }

            error.message!! shouldContain "UUID"
        }

        "an invalid branch name is refused at save time" {
            val parameters = validParameters().also { it[GitRepositoryIntegration.PARAM_MAIN_BRANCH] = "not a branch" }

            shouldThrow<BadRequestException> { newService().create(config(parameters)) }
        }

        "updating an association to something unusable is refused too" {
            val service = newService()
            val saved = service.create(config(validParameters()))
            val broken =
                saved.copy(
                    parameters =
                        objectMapper.valueToTree(
                            validParameters().also { it[GitRepositoryIntegration.PARAM_REPOSITORY_URL] = "ext::sh -c whoami" },
                        ),
                )

            clearMocks(provisioner, answers = false)
            shouldThrow<BadRequestException> { service.update(broken) }
            verify(exactly = 0) { provisioner.requestPreparation(any()) }
        }

        "generic integration updates cannot repoint an existing checkout" {
            val service = newService()
            val saved = service.create(config(validParameters()))
            every { checkouts.findByNamespaceId(namespaceId) } returns RepositoryCheckout(
                namespaceId = namespaceId, integrationConfigId = saved.id,
                repositoryUrl = "https://forge.example/org/project.git", mainBranch = "main",
            )
            for ((key, value) in listOf(
                GitRepositoryIntegration.PARAM_REPOSITORY_URL to "https://forge.example/org/other.git",
                GitRepositoryIntegration.PARAM_MAIN_BRANCH to "develop",
            )) {
                val changed = saved.copy(parameters = objectMapper.valueToTree(validParameters().also { it[key] = value }))
                shouldThrow<ConflictException> { service.update(changed) }
                service.findById(saved.id)?.parameters shouldBe saved.parameters
            }
            val accountChange = saved.copy(parameters = objectMapper.valueToTree(validParameters().also {
                it[GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID] = UUID.randomUUID().toString()
            }))
            service.update(accountChange).parameters shouldBe accountChange.parameters
        }

        "a configuration of another type is untouched by this policy" {
            policy.supports("JIRA") shouldBe false
            newService().create(config(mapOf("anything" to "goes"), name = "JIRA", integrationType = "JIRA")).id.shouldNotBeNull()
            verify(exactly = 0) { provisioner.requestPreparation(any()) }
        }
    })

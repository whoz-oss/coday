package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.integrationConfig.InMemoryIntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl
import java.util.UUID

/**
 * A `GIT_REPOSITORY` configuration is validated when it is **saved**, not when it is later read.
 *
 * Without this the generic CRUD stores any JSON in `parameters` and answers 201, so a malformed
 * association only fails much later, during provisioning, far from the mistake.
 */
class GitRepositoryConfigValidatorSpec :
    StringSpec({

        val objectMapper = ObjectMapper()
        val checkouts = mockk<RepositoryCheckoutService>()
        beforeTest { every { checkouts.findByNamespaceId(any()) } returns null }
        val validator = GitRepositoryConfigValidator(GitRepositorySettingsFactory(GitRemoteUrlValidator(GitExecutionProperties())), checkouts)

        fun newService(): IntegrationConfigServiceImpl =
            IntegrationConfigServiceImpl(
                InMemoryIntegrationConfigRepository(),
                IntegrationConfigMergeStrategy(),
                listOf(validator),
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

            shouldThrow<BadRequestException> { service.update(broken) }
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
            val credentials = saved.copy(parameters = objectMapper.valueToTree(validParameters().also {
                it[GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID] = UUID.randomUUID().toString()
            }))
            service.update(credentials).parameters shouldBe credentials.parameters
        }

        "a configuration of another type is untouched by this validator" {
            validator.supports("JIRA") shouldBe false
            newService().create(config(mapOf("anything" to "goes"), name = "JIRA", integrationType = "JIRA")).id.shouldNotBeNull()
        }
    })

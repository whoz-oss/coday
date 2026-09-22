package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import java.util.UUID

class GitRepositorySettingsFactorySpec :
    StringSpec({

        val objectMapper = ObjectMapper()
        val factory = GitRepositorySettingsFactory(GitRemoteUrlValidator(GitExecutionProperties()))

        val namespaceId = UUID.randomUUID()
        val authSettingId = UUID.randomUUID()

        fun config(
            parameters: Map<String, Any?>,
            namespace: UUID? = namespaceId,
            user: UUID? = null,
        ): IntegrationConfig =
            IntegrationConfig(
                namespaceId = namespace,
                userId = user,
                name = "project-repository",
                integrationType = GitRepositoryIntegration.TYPE,
                parameters = objectMapper.valueToTree(parameters),
            )

        fun validParameters(): MutableMap<String, Any?> =
            mutableMapOf(
                GitRepositoryIntegration.PARAM_REPOSITORY_URL to "https://github.com/org/project.git",
                GitRepositoryIntegration.PARAM_MAIN_BRANCH to "main",
                GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID to authSettingId.toString(),
            )

        "a complete configuration parses" {
            val settings =
                factory.fromConfig(
                    config(validParameters().also { it[GitRepositoryIntegration.PARAM_AUTO_WORKTREE] = true }),
                )

            settings.namespaceId shouldBe namespaceId
            settings.repositoryUrl shouldBe "https://github.com/org/project.git"
            settings.mainBranch shouldBe "main"
            settings.serviceAuthSettingId shouldBe authSettingId
            settings.autoWorktreeForRootCases shouldBe true
            settings.setupCommand shouldBe null
        }

        "automation is off unless explicitly enabled" {
            factory.fromConfig(config(validParameters())).autoWorktreeForRootCases shouldBe false
        }

        "the main branch defaults when absent" {
            val parameters = validParameters().also { it.remove(GitRepositoryIntegration.PARAM_MAIN_BRANCH) }
            factory.fromConfig(config(parameters)).mainBranch shouldBe GitRepositoryIntegration.DEFAULT_MAIN_BRANCH
        }

        "a user-scoped row is refused: it would let a member redirect provisioning" {
            val error = shouldThrow<BadRequestException> { factory.fromConfig(config(validParameters(), user = UUID.randomUUID())) }
            error.message!! shouldContain "namespace-shared"
        }

        "a platform-scoped row is refused" {
            shouldThrow<BadRequestException> { factory.fromConfig(config(validParameters(), namespace = null)) }
        }

        "the auth setting must be a UUID, never a name" {
            val parameters =
                validParameters().also {
                    it[GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID] = "git-service-account"
                }
            val error = shouldThrow<BadRequestException> { factory.fromConfig(config(parameters)) }
            error.message!! shouldContain "UUID"
        }

        "a missing auth setting is refused" {
            val parameters = validParameters().also { it.remove(GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID) }
            shouldThrow<BadRequestException> { factory.fromConfig(config(parameters)) }
        }

        "an invalid repository URL is refused" {
            val parameters = validParameters().also { it[GitRepositoryIntegration.PARAM_REPOSITORY_URL] = "git@github.com:org/repo.git" }
            shouldThrow<BadRequestException> { factory.fromConfig(config(parameters)) }
        }

        "an invalid main branch is refused" {
            val parameters = validParameters().also { it[GitRepositoryIntegration.PARAM_MAIN_BRANCH] = "not a branch" }
            val error = shouldThrow<BadRequestException> { factory.fromConfig(config(parameters)) }
            error.message!! shouldContain "not a valid branch name"
        }

        "a configuration without parameters is refused" {
            val bare =
                IntegrationConfig(
                    namespaceId = namespaceId,
                    name = "project-repository",
                    integrationType = GitRepositoryIntegration.TYPE,
                )
            shouldThrow<BadRequestException> { factory.fromConfig(bare) }
        }

        "an oversized setup command is refused" {
            val parameters =
                validParameters().also {
                    it[GitRepositoryIntegration.PARAM_SETUP_COMMAND] = "x".repeat(GitRepositorySettingsFactory.MAX_SETUP_COMMAND_LENGTH + 1)
                }
            shouldThrow<BadRequestException> { factory.fromConfig(config(parameters)) }
        }
    })

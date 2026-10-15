package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitCredentials
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitRemoteUrlValidator
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.UUID

class GitServiceAccountResolverSpec : StringSpec({
    val namespaceId = UUID.randomUUID()
    val settings = GitRepositorySettings(UUID.randomUUID(), namespaceId, "https://example.com/repo.git", "main", UUID.randomUUID(), autoWorktreeForRootCases = false, setupCommand = null)
    val authSettings = mockk<AuthSettingService>()
    val resolver = GitServiceAccountResolver(authSettings)
    val shared = BearerTokenAuthSetting(namespaceId = namespaceId, name = "service", token = "fixture-token")

    "a namespace shared token resolves by its configured UUID" {
        every { authSettings.findById(settings.serviceAuthSettingId, any()) } returns shared
        resolver.resolve(settings) shouldBe GitCredentials.UsernamePassword("x-access-token", "fixture-token")
    }
    "personal and out of namespace credentials are refused" {
        listOf(shared.copy(userId = UUID.randomUUID()), shared.copy(namespaceId = UUID.randomUUID()), shared.copy(namespaceId = null)).forEach { auth ->
            every { authSettings.findById(settings.serviceAuthSettingId, any()) } returns auth
            shouldThrow<BadRequestException> { resolver.resolve(settings) }
        }
    }
    "deleted or empty service credentials fail before a Git process starts" {
        every { authSettings.findById(settings.serviceAuthSettingId, any()) } returns null
        shouldThrow<BadRequestException> { resolver.resolve(settings) }
        every { authSettings.findById(settings.serviceAuthSettingId, any()) } returns shared.copy(token = "")
        shouldThrow<BadRequestException> { resolver.resolve(settings) }
    }
    "control characters in Git credentials are rejected without exposing their values" {
        listOf("\n", "\r", "\u0000", "\t", "\u007f", "\u0085").forEach { control ->
            val secret = "synthetic-git-token$control"
            every { authSettings.findById(settings.serviceAuthSettingId, any()) } returns shared.copy(token = secret)
            val failure = shouldThrow<BadRequestException> { resolver.resolve(settings) }
            failure.message!! shouldNotContain "synthetic-git-token"
            failure.cause shouldBe null
        }
    }
    "valid credentials are not silently trimmed" {
        val secret = " valid-password "
        every { authSettings.findById(settings.serviceAuthSettingId, any()) } returns shared.copy(token = secret)
        resolver.resolve(settings).secret shouldBe secret
    }
    "existing workspace snapshots use a replacement namespace service account" {
        val replacementId = UUID.randomUUID()
        val associations = mockk<GitRepositoryAssociationService> {
            every { findSettings(namespaceId) } returns settings.copy(serviceAuthSettingId = replacementId)
        }
        val store = mockk<AuthSettingService> {
            every { findById(replacementId, any()) } returns shared.copy(token = "replacement-token")
        }
        GitServiceAccountResolver(store, associations).resolve(settings) shouldBe
            GitCredentials.UsernamePassword("x-access-token", "replacement-token")
        verify(exactly = 0) { store.findById(settings.serviceAuthSettingId, any()) }
    }

    "the real Spring dependency graph starts and resolves the current association lazily" {
        val replacementId = UUID.randomUUID()
        val config = IntegrationConfig(
            namespaceId = namespaceId,
            name = "git",
            integrationType = GitRepositoryIntegration.TYPE,
            parameters = jacksonObjectMapper().createObjectNode()
                .put(GitRepositoryIntegration.PARAM_REPOSITORY_URL, settings.repositoryUrl)
                .put(GitRepositoryIntegration.PARAM_MAIN_BRANCH, settings.mainBranch)
                .put(GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID, replacementId.toString()),
        )
        val repository = mockk<IntegrationConfigRepository> {
            every { findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE) } returns config
        }
        val store = mockk<AuthSettingService> {
            every { findById(replacementId, any()) } returns shared.copy(token = "replacement-token")
        }
        AnnotationConfigApplicationContext().use { context ->
            // Keep the actual resolver -> association -> config service -> policy -> checkout
            // provisioner -> resolver graph. Only persistence and filesystem/Git leaves are fake.
            context.beanFactory.registerSingleton("authSettingService", store)
            context.beanFactory.registerSingleton("integrationConfigRepository", repository)
            context.beanFactory.registerSingleton("checkoutService", mockk<RepositoryCheckoutService>())
            context.beanFactory.registerSingleton("bindingService", mockk<CaseResourceBindingService>())
            context.beanFactory.registerSingleton("storage", mockk<ExchangeStorageService>())
            context.beanFactory.registerSingleton("runner", mockk<GitCommandRunner>())
            context.beanFactory.registerSingleton("gitProperties", GitExecutionProperties())
            context.beanFactory.registerSingleton("urlValidator", mockk<GitRemoteUrlValidator>())
            context.beanFactory.registerSingleton("gitAvailability", mockk<GitAvailability>(relaxed = true))
            context.register(
                GitServiceAccountResolver::class.java,
                GitRepositoryAssociationService::class.java,
                IntegrationConfigServiceImpl::class.java,
                IntegrationConfigMergeStrategy::class.java,
                GitRepositoryConfigPolicy::class.java,
                RepositoryCheckoutProvisioner::class.java,
                GitRepositorySettingsFactory::class.java,
            )
            context.refresh()
            context.getBean(GitServiceAccountResolver::class.java).resolve(settings) shouldBe
                GitCredentials.UsernamePassword("x-access-token", "replacement-token")
            verify(exactly = 0) { store.findById(settings.serviceAuthSettingId, any()) }
            verify(exactly = 1) { repository.findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE) }
        }
    }

    "removed associations keep the historical account without borrowing a different repository identity" {
        val associations = mockk<GitRepositoryAssociationService> { every { findSettings(namespaceId) } returns null }
        val store = mockk<AuthSettingService> { every { findById(settings.serviceAuthSettingId, any()) } returns shared }
        val currentResolver = GitServiceAccountResolver(store, associations)
        currentResolver.resolve(settings) shouldBe GitCredentials.UsernamePassword("x-access-token", "fixture-token")
        every { associations.findSettings(namespaceId) } returns settings.copy(repositoryUrl = "https://different.example/repo.git")
        shouldThrow<BadRequestException> { currentResolver.resolve(settings) }
    }

})

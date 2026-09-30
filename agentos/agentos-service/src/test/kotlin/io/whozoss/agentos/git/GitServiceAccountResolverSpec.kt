package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.git.core.GitCredentials
import java.util.UUID

class GitServiceAccountResolverSpec : StringSpec({
    val namespaceId = UUID.randomUUID()
    val settings = GitRepositorySettings(UUID.randomUUID(), namespaceId, "https://example.com/repo.git", "main", UUID.randomUUID())
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


    "removed associations keep the historical account without borrowing a different repository identity" {
        val associations = mockk<GitRepositoryAssociationService> { every { findSettings(namespaceId) } returns null }
        val store = mockk<AuthSettingService> { every { findById(settings.serviceAuthSettingId, any()) } returns shared }
        val currentResolver = GitServiceAccountResolver(store, associations)
        currentResolver.resolve(settings) shouldBe GitCredentials.UsernamePassword("x-access-token", "fixture-token")
        every { associations.findSettings(namespaceId) } returns settings.copy(repositoryUrl = "https://different.example/repo.git")
        shouldThrow<BadRequestException> { currentResolver.resolve(settings) }
    }

})

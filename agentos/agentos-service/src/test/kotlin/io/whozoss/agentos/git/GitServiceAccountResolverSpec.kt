package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.exception.BadRequestException
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
})

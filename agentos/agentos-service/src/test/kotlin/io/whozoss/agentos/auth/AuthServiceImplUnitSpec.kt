package io.whozoss.agentos.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.AuthType
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [AuthServiceImpl].
 *
 * Verifies that each method delegates to the correct underlying service with the
 * correct parameters, and that exceptions from the underlying services propagate
 * unchanged.
 */
class AuthServiceImplUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()
    val authSettingId: UUID = UUID.randomUUID()

    fun authSetting(name: String = "my-setting") = AuthSetting(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        userId = null,
        name = name,
        authType = AuthType.OAUTH_DISCOVERABLE,
    )

    fun credential() = Credential(
        metadata = EntityMetadata(),
        userId = userId,
        authSettingId = authSettingId,
        credentialType = CredentialType.OAUTH_TOKENS,
        data = mapOf("accessToken" to "tok"),
    )

    fun buildService(
        authSettingService: AuthSettingService = mockk(),
        credentialService: CredentialService = mockk(),
    ) = AuthServiceImpl(namespaceId, userId, authSettingService, credentialService)

    // -------------------------------------------------------------------------
    // resolveAuthSetting
    // -------------------------------------------------------------------------

    "resolveAuthSetting delegates to AuthSettingService with correct (namespaceId, userId, name)" {
        val setting = authSetting("github-oauth")
        val authSettingService = mockk<AuthSettingService> {
            every { resolveAuthSetting(namespaceId, userId, "github-oauth") } returns setting
        }
        val svc = buildService(authSettingService = authSettingService)

        val result = svc.resolveAuthSetting("github-oauth")

        result shouldBe setting
        verify(exactly = 1) { authSettingService.resolveAuthSetting(namespaceId, userId, "github-oauth") }
    }

    "resolveAuthSetting propagates ConfigNotFoundException from the underlying service" {
        val authSettingService = mockk<AuthSettingService> {
            every { resolveAuthSetting(any(), any(), any()) } throws
                ConfigNotFoundException(namespaceId, userId, "missing")
        }
        val svc = buildService(authSettingService = authSettingService)

        shouldThrow<ConfigNotFoundException> {
            svc.resolveAuthSetting("missing")
        }
    }

    // -------------------------------------------------------------------------
    // resolveCredential
    // -------------------------------------------------------------------------

    "resolveCredential delegates to CredentialService with correct (userId, authSettingId)" {
        val cred = credential()
        val credentialService = mockk<CredentialService> {
            every { resolve(userId, authSettingId) } returns cred
        }
        val svc = buildService(credentialService = credentialService)

        val result = svc.resolveCredential(authSettingId)

        result shouldBe cred
        verify(exactly = 1) { credentialService.resolve(userId, authSettingId) }
    }

    "resolveCredential returns null when no credential exists" {
        val credentialService = mockk<CredentialService> {
            every { resolve(userId, authSettingId) } returns null
        }
        val svc = buildService(credentialService = credentialService)

        svc.resolveCredential(authSettingId).shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // storeCredential
    // -------------------------------------------------------------------------

    "storeCredential delegates to CredentialService.store and returns the stored credential" {
        val cred = credential()
        val stored = cred.copy(metadata = EntityMetadata())
        val credentialService = mockk<CredentialService> {
            every { store(cred) } returns stored
        }
        val svc = buildService(credentialService = credentialService)

        val result = svc.storeCredential(cred)

        result shouldBe stored
        verify(exactly = 1) { credentialService.store(cred) }
    }

    // -------------------------------------------------------------------------
    // revokeCredential
    // -------------------------------------------------------------------------

    "revokeCredential delegates to CredentialService.revoke with correct (userId, authSettingId) and returns true" {
        val credentialService = mockk<CredentialService> {
            every { revoke(userId, authSettingId) } returns true
        }
        val svc = buildService(credentialService = credentialService)

        val result = svc.revokeCredential(authSettingId)

        result shouldBe true
        verify(exactly = 1) { credentialService.revoke(userId, authSettingId) }
    }

    "revokeCredential returns false when no credential existed" {
        val credentialService = mockk<CredentialService> {
            every { revoke(userId, authSettingId) } returns false
        }
        val svc = buildService(credentialService = credentialService)

        svc.revokeCredential(authSettingId) shouldBe false
    }
})

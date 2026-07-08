package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.AuthType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [AuthServiceFactory].
 *
 * Verifies that [AuthServiceFactory.create] returns an [AuthService] instance
 * that is correctly scoped to the provided (namespaceId, userId) and properly
 * delegates to the injected services.
 */
class AuthServiceFactoryUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()

    fun authSetting(name: String = "test-setting") = AuthSetting(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        userId = null,
        name = name,
        authType = AuthType.OAUTH_DISCOVERABLE,
    )

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    "create returns an AuthService instance" {
        val factory = AuthServiceFactory(
            authSettingService = mockk(relaxed = true),
            credentialService = mockk(relaxed = true),
        )

        val service = factory.create(namespaceId, userId)

        service.shouldBeInstanceOf<AuthServiceImpl>()
    }

    "created instance delegates to the injected AuthSettingService with the factory-provided namespaceId and userId" {
        val setting = authSetting("github-oauth")
        val authSettingService = mockk<AuthSettingService> {
            every { resolveAuthSetting(namespaceId, userId, "github-oauth") } returns setting
        }
        val factory = AuthServiceFactory(
            authSettingService = authSettingService,
            credentialService = mockk(relaxed = true),
        )

        val result = factory.create(namespaceId, userId).resolveAuthSetting("github-oauth")

        result shouldBe setting
    }

    "create with different (namespaceId, userId) pairs produces independent instances" {
        val nsA = UUID.randomUUID()
        val nsB = UUID.randomUUID()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val settingA = authSetting("setting-a")
        val settingB = authSetting("setting-b")
        val authSettingService = mockk<AuthSettingService> {
            every { resolveAuthSetting(nsA, userA, "setting-a") } returns settingA
            every { resolveAuthSetting(nsB, userB, "setting-b") } returns settingB
        }
        val factory = AuthServiceFactory(
            authSettingService = authSettingService,
            credentialService = mockk(relaxed = true),
        )

        val serviceA = factory.create(nsA, userA)
        val serviceB = factory.create(nsB, userB)

        serviceA.resolveAuthSetting("setting-a") shouldBe settingA
        serviceB.resolveAuthSetting("setting-b") shouldBe settingB
    }
})

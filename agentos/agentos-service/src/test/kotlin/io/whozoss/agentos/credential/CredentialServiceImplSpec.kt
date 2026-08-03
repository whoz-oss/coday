package io.whozoss.agentos.credential

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class CredentialServiceImplSpec : StringSpec() {
    private fun credential(
        userId: UUID = UUID.randomUUID(),
        authSettingId: UUID = UUID.randomUUID(),
        credentialType: CredentialType = CredentialType.API_KEY,
        data: Map<String, String> = mapOf("key" to "secret-value"),
    ): Credential =
        Credential(
            metadata = EntityMetadata(),
            userId = userId,
            authSettingId = authSettingId,
            credentialType = credentialType,
            data = data,
        )

    init {
        "store delegates to repository and returns the saved credential" {
            val repository = mockk<CredentialRepository>()
            val service = CredentialServiceImpl(repository)
            val cred = credential()
            every { repository.save(cred) } returns cred

            val result = service.store(cred)

            result shouldBe cred
            verify(exactly = 1) { repository.save(cred) }
        }

        "resolve delegates to repository" {
            val repository = mockk<CredentialRepository>()
            val service = CredentialServiceImpl(repository)
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            val cred = credential(userId = userId, authSettingId = authSettingId)
            every { repository.findByUserAndAuthSetting(userId, authSettingId) } returns cred

            val result = service.resolve(userId, authSettingId)

            result.shouldNotBeNull()
            result shouldBe cred
        }

        "resolve returns null when repository returns null" {
            val repository = mockk<CredentialRepository>()
            val service = CredentialServiceImpl(repository)
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            every { repository.findByUserAndAuthSetting(userId, authSettingId) } returns null

            service.resolve(userId, authSettingId).shouldBeNull()
        }

        "delete delegates to repository and returns true when credential existed" {
            val repository = mockk<CredentialRepository>()
            val service = CredentialServiceImpl(repository)
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            every { repository.deleteByUserAndAuthSetting(userId, authSettingId) } returns true

            service.delete(userId, authSettingId) shouldBe true
            verify(exactly = 1) { repository.deleteByUserAndAuthSetting(userId, authSettingId) }
        }

        "delete returns false when repository reports no credential" {
            val repository = mockk<CredentialRepository>()
            val service = CredentialServiceImpl(repository)
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            every { repository.deleteByUserAndAuthSetting(userId, authSettingId) } returns false

            service.delete(userId, authSettingId) shouldBe false
        }

        "deleteByAuthSetting delegates and returns the count from repository" {
            val repository = mockk<CredentialRepository>()
            val service = CredentialServiceImpl(repository)
            val authSettingId = UUID.randomUUID()
            every { repository.deleteByAuthSettingId(authSettingId) } returns 3

            service.deleteByAuthSetting(authSettingId) shouldBe 3
            verify(exactly = 1) { repository.deleteByAuthSettingId(authSettingId) }
        }

        "findByUserId delegates to repository" {
            val repository = mockk<CredentialRepository>()
            val service = CredentialServiceImpl(repository)
            val userId = UUID.randomUUID()
            val creds = listOf(credential(userId = userId), credential(userId = userId))
            every { repository.findByUserId(userId) } returns creds

            val result = service.findByUserId(userId)

            result shouldHaveSize 2
            verify(exactly = 1) { repository.findByUserId(userId) }
        }

        "findByUserId returns empty list when repository returns empty" {
            val repository = mockk<CredentialRepository>()
            val service = CredentialServiceImpl(repository)
            val userId = UUID.randomUUID()
            every { repository.findByUserId(userId) } returns emptyList()

            service.findByUserId(userId).shouldBeEmpty()
        }
    }
}

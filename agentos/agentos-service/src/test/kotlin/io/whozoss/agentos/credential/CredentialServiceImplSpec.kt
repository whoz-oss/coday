package io.whozoss.agentos.credential

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class CredentialServiceImplSpec : StringSpec() {
    private fun newService() = CredentialServiceImpl(InMemoryCredentialRepository())

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
        "store a credential and resolve it" {
            val service = newService()
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            val cred = credential(userId = userId, authSettingId = authSettingId)

            service.store(cred)

            val resolved = service.resolve(userId, authSettingId)
            resolved.shouldNotBeNull()
            resolved.userId shouldBe userId
            resolved.authSettingId shouldBe authSettingId
        }

        "store overwrites existing credential for same (userId, authSettingId)" {
            val service = newService()
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            val first = credential(userId = userId, authSettingId = authSettingId, data = mapOf("key" to "old-value"))
            val second = credential(userId = userId, authSettingId = authSettingId, data = mapOf("key" to "new-value"))

            service.store(first)
            service.store(second)

            // Only the second credential should be visible
            val resolved = service.resolve(userId, authSettingId)
            resolved.shouldNotBeNull()
            resolved.data["key"] shouldBe "new-value"
        }

        "resolve returns null when not found" {
            val service = newService()

            service.resolve(UUID.randomUUID(), UUID.randomUUID()).shouldBeNull()
        }

        "revoke deletes the credential" {
            val service = newService()
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            service.store(credential(userId = userId, authSettingId = authSettingId))

            service.revoke(userId, authSettingId) shouldBe true
            service.resolve(userId, authSettingId).shouldBeNull()
        }

        "revoke returns false when credential does not exist" {
            val service = newService()

            service.revoke(UUID.randomUUID(), UUID.randomUUID()) shouldBe false
        }

        "revokeByAuthSetting cascades to all credentials for that authSetting" {
            val service = newService()
            val authSettingId = UUID.randomUUID()
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            val unrelated = UUID.randomUUID()

            service.store(credential(userId = userA, authSettingId = authSettingId))
            service.store(credential(userId = userB, authSettingId = authSettingId))
            service.store(credential(userId = userA, authSettingId = unrelated))

            service.revokeByAuthSetting(authSettingId) shouldBe 2
            service.resolve(userA, authSettingId).shouldBeNull()
            service.resolve(userB, authSettingId).shouldBeNull()
            // Unrelated credential is untouched
            service.resolve(userA, unrelated).shouldNotBeNull()
        }

        "findByUserId returns only that user's credentials" {
            val service = newService()
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()

            service.store(credential(userId = userA, authSettingId = UUID.randomUUID()))
            service.store(credential(userId = userA, authSettingId = UUID.randomUUID()))
            service.store(credential(userId = userB, authSettingId = UUID.randomUUID()))

            service.findByUserId(userA) shouldHaveSize 2
            service.findByUserId(userB) shouldHaveSize 1
            service.findByUserId(UUID.randomUUID()).shouldBeEmpty()
        }

        "data is preserved correctly through store/resolve cycle" {
            val service = newService()
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            val data = mapOf(
                "accessToken" to "tok_abc",
                "refreshToken" to "ref_xyz",
                "expiresAt" to "2099-01-01T00:00:00Z",
                "tokenType" to "Bearer",
                "scope" to "read write",
            )
            val cred = credential(
                userId = userId,
                authSettingId = authSettingId,
                credentialType = CredentialType.OAUTH_TOKENS,
                data = data,
            )

            service.store(cred)

            val resolved = service.resolve(userId, authSettingId)
            resolved.shouldNotBeNull()
            resolved.credentialType shouldBe CredentialType.OAUTH_TOKENS
            resolved.data shouldBe data
        }
    }
}

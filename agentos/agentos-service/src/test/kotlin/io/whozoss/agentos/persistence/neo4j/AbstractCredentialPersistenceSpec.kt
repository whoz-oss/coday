package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty as mapShouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.credential.CredentialRepository
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Shared credential persistence contract tests.
 *
 * Subclasses activate a specific persistence mode (embedded harness or Testcontainers)
 * and inherit all test cases, ensuring both modes satisfy the same contract.
 *
 * [Credential] nodes have no parent relationship in the graph — they are keyed on the
 * `(userId, authSettingId)` pair. No pre-existing parent node is required.
 *
 * Credentials are **hard-deleted**: no soft-delete lifecycle, no `removed` flag.
 */
abstract class AbstractCredentialPersistenceSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: CredentialRepository

    @Autowired
    lateinit var driver: Driver

    fun credential(
        userId: UUID = UUID.randomUUID(),
        authSettingId: UUID = UUID.randomUUID(),
        credentialType: CredentialType = CredentialType.API_KEY,
        data: Map<String, String> = mapOf("key" to "secret-value"),
    ) = Credential(
        metadata = EntityMetadata(),
        userId = userId,
        authSettingId = authSettingId,
        credentialType = credentialType,
        data = data,
    )

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "save and findByUserAndAuthSetting returns the same credential" {
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            val saved = repo.save(credential(userId = userId, authSettingId = authSettingId))

            val found = repo.findByUserAndAuthSetting(userId, authSettingId)

            found.shouldNotBeNull()
            found.id shouldBe saved.id
            found.userId shouldBe userId
            found.authSettingId shouldBe authSettingId
        }

        "save has upsert semantics: second save replaces the first for the same pair" {
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            repo.save(credential(userId = userId, authSettingId = authSettingId, data = mapOf("key" to "old")))
            repo.save(credential(userId = userId, authSettingId = authSettingId, data = mapOf("key" to "new")))

            val found = repo.findByUserAndAuthSetting(userId, authSettingId)

            found.shouldNotBeNull()
            found.data["key"] shouldBe "new"
        }

        "findByUserAndAuthSetting returns null when no credential exists" {
            repo.findByUserAndAuthSetting(UUID.randomUUID(), UUID.randomUUID()).shouldBeNull()
        }

        "data map round-trips correctly" {
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            val data = mapOf(
                "accessToken" to "tok_abc",
                "refreshToken" to "ref_xyz",
                "expiresAt" to "2099-01-01T00:00:00Z",
                "tokenType" to "Bearer",
                "scope" to "read write",
            )
            repo.save(credential(userId = userId, authSettingId = authSettingId, credentialType = CredentialType.OAUTH_TOKENS, data = data))

            val found = repo.findByUserAndAuthSetting(userId, authSettingId)

            found.shouldNotBeNull()
            found.credentialType shouldBe CredentialType.OAUTH_TOKENS
            found.data shouldBe data
        }

        "empty data map round-trips correctly" {
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            repo.save(credential(userId = userId, authSettingId = authSettingId, data = emptyMap()))

            val found = repo.findByUserAndAuthSetting(userId, authSettingId)

            found.shouldNotBeNull()
            found.data.mapShouldBeEmpty()
        }

        "deleteByUserAndAuthSetting hard-deletes the credential" {
            val userId = UUID.randomUUID()
            val authSettingId = UUID.randomUUID()
            repo.save(credential(userId = userId, authSettingId = authSettingId))

            val deleted = repo.deleteByUserAndAuthSetting(userId, authSettingId)

            deleted shouldBe true
            repo.findByUserAndAuthSetting(userId, authSettingId).shouldBeNull()
        }

        "deleteByUserAndAuthSetting returns false when no credential exists" {
            repo.deleteByUserAndAuthSetting(UUID.randomUUID(), UUID.randomUUID()) shouldBe false
        }

        "deleteByUserAndAuthSetting only removes the targeted credential" {
            val userId = UUID.randomUUID()
            val authSettingId1 = UUID.randomUUID()
            val authSettingId2 = UUID.randomUUID()
            repo.save(credential(userId = userId, authSettingId = authSettingId1))
            repo.save(credential(userId = userId, authSettingId = authSettingId2))

            repo.deleteByUserAndAuthSetting(userId, authSettingId1)

            repo.findByUserAndAuthSetting(userId, authSettingId1).shouldBeNull()
            repo.findByUserAndAuthSetting(userId, authSettingId2).shouldNotBeNull()
        }

        "deleteByAuthSettingId cascade-deletes all credentials for that authSetting" {
            val authSettingId = UUID.randomUUID()
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            val unrelatedAuthSetting = UUID.randomUUID()
            repo.save(credential(userId = userA, authSettingId = authSettingId))
            repo.save(credential(userId = userB, authSettingId = authSettingId))
            repo.save(credential(userId = userA, authSettingId = unrelatedAuthSetting))

            val count = repo.deleteByAuthSettingId(authSettingId)

            count shouldBe 2
            repo.findByUserAndAuthSetting(userA, authSettingId).shouldBeNull()
            repo.findByUserAndAuthSetting(userB, authSettingId).shouldBeNull()
            repo.findByUserAndAuthSetting(userA, unrelatedAuthSetting).shouldNotBeNull()
        }

        "deleteByAuthSettingId returns 0 when no credentials exist for that authSetting" {
            repo.deleteByAuthSettingId(UUID.randomUUID()) shouldBe 0
        }

        "findByUserId returns all credentials for that user only" {
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            repo.save(credential(userId = userA, authSettingId = UUID.randomUUID()))
            repo.save(credential(userId = userA, authSettingId = UUID.randomUUID()))
            repo.save(credential(userId = userB, authSettingId = UUID.randomUUID()))

            repo.findByUserId(userA) shouldHaveSize 2
            repo.findByUserId(userB) shouldHaveSize 1
            repo.findByUserId(UUID.randomUUID()).shouldBeEmpty()
        }
    }
}

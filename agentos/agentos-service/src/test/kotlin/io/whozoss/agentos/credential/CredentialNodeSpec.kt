package io.whozoss.agentos.credential

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [CredentialNode.fromDomain] and [CredentialNode.toDomain].
 *
 * All tests are pure round-trip / mapping exercises — no Spring context needed.
 * Encryption is not the Node's responsibility; those concerns live in [Neo4jCredentialRepository].
 * [jacksonObjectMapper] is used directly here; production code uses the Spring-managed bean.
 */
class CredentialNodeSpec : StringSpec({
    timeout = 5000

    val mapper = jacksonObjectMapper()

    val fixedNow = Instant.parse("2025-01-15T10:00:00Z")
    val fixedModified = Instant.parse("2025-01-16T12:30:00Z")
    val userId = UUID.randomUUID()
    val authSettingId = UUID.randomUUID()
    val metadataId = UUID.randomUUID()

    fun baseMetadata() = EntityMetadata(
        id = metadataId,
        created = fixedNow,
        createdBy = "alice",
        modified = fixedModified,
        modifiedBy = "bob",
        removed = false,
    )

    fun baseCredential(
        data: Map<String, String> = mapOf("accessToken" to "tok-abc", "refreshToken" to "ref-xyz"),
        type: CredentialType = CredentialType.OAUTH_TOKENS,
    ) = Credential(
        metadata = baseMetadata(),
        userId = userId,
        authSettingId = authSettingId,
        credentialType = type,
        data = data,
    )

    // round-trip

    "round-trip preserves all fields" {
        val original = baseCredential()
        val node = CredentialNode.fromDomain(original, mapper)
        val restored = node.toDomain(mapper)

        restored.id shouldBe original.id
        restored.userId shouldBe original.userId
        restored.authSettingId shouldBe original.authSettingId
        restored.credentialType shouldBe original.credentialType
        restored.data shouldContainExactly original.data
    }

    // round-trip with empty data map

    "round-trip preserves empty data map" {
        val original = baseCredential(data = emptyMap())
        val node = CredentialNode.fromDomain(original, mapper)
        val restored = node.toDomain(mapper)

        node.dataJson shouldBe null
        restored.data.shouldBeEmpty()
    }

    // all CredentialType values survive the round-trip

    CredentialType.entries.forEach { type ->
        "CredentialType.$type survives round-trip" {
            val original = baseCredential(type = type)
            val restored = CredentialNode.fromDomain(original, mapper).toDomain(mapper)
            restored.credentialType shouldBe type
        }
    }

    // EntityMetadata fields are preserved

    "EntityMetadata fields are fully preserved across round-trip" {
        val original = baseCredential()
        val restored = CredentialNode.fromDomain(original, mapper).toDomain(mapper)

        restored.metadata.id shouldBe original.metadata.id
        restored.metadata.created shouldBe original.metadata.created
        restored.metadata.createdBy shouldBe original.metadata.createdBy
        restored.metadata.modified shouldBe original.metadata.modified
        restored.metadata.modifiedBy shouldBe original.metadata.modifiedBy
        restored.metadata.removed shouldBe original.metadata.removed
    }

})

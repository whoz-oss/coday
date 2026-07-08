package io.whozoss.agentos.credential

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.encryption.FieldEncryptor
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Credential].
 *
 * Uniqueness is on the `(userId, authSettingId)` pair — there is no tripleKey
 * discriminator, because credentials have a simpler identity model than [AuthSetting].
 *
 * [dataJson] stores the [Credential.data] map serialised as JSON. Each value is
 * individually **encrypted** before write and **decrypted** after read using the
 * provided [FieldEncryptor]. Because [CredentialNode] is a plain data class (not a
 * Spring bean), the encryptor is passed explicitly to [toDomain] and [fromDomain].
 *
 * [credentialType] is stored as its enum name string and round-tripped via
 * [CredentialType.valueOf].
 */
@Node("Credential")
data class CredentialNode(
    @Id
    val id: String,
    val userId: String,
    val authSettingId: String,
    val credentialType: String,
    /**
     * JSON-serialised [Credential.data] map. Each map value is encrypted at rest;
     * decryption happens in [toDomain] via the caller-supplied [FieldEncryptor].
     */
    val dataJson: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(encryptor: FieldEncryptor): Credential {
        val rawData: Map<String, String> =
            dataJson?.let { MAPPER.readValue(it, DATA_TYPE) } ?: emptyMap()
        val decryptedData = rawData.mapValues { (_, v) -> encryptor.decrypt(v) }
        return Credential(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            userId = UUID.fromString(userId),
            authSettingId = UUID.fromString(authSettingId),
            credentialType = CredentialType.valueOf(credentialType),
            data = decryptedData,
        )
    }

    companion object {
        private val MAPPER = jacksonObjectMapper()
        private val DATA_TYPE = object : TypeReference<Map<String, String>>() {}

        fun fromDomain(
            credential: Credential,
            encryptor: FieldEncryptor,
        ): CredentialNode {
            val encryptedData = credential.data.mapValues { (_, v) -> encryptor.encrypt(v) }
            return CredentialNode(
                id = credential.id.toString(),
                userId = credential.userId.toString(),
                authSettingId = credential.authSettingId.toString(),
                credentialType = credential.credentialType.name,
                dataJson = encryptedData.takeIf { it.isNotEmpty() }?.let { MAPPER.writeValueAsString(it) },
                created = credential.metadata.created,
                createdBy = credential.metadata.createdBy,
                modified = credential.metadata.modified,
                modifiedBy = credential.metadata.modifiedBy,
                removed = credential.metadata.removed.takeIf { it },
            )
        }
    }
}

package io.whozoss.agentos.credential

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
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
 * [dataJson] stores the [Credential.data] map serialised as JSON. Encryption and
 * decryption of individual values is handled by the caller ([Neo4jCredentialRepository]),
 * not by this node class. [CredentialNode] is a pure data holder.
 *
 * [credentialType] is stored as its enum name string and round-tripped via
 * [CredentialType.valueOf].
 *
 * Credentials are **hard-deleted** — there is no `removed` field and no soft-delete
 * lifecycle. Sensitive credential material must not linger in the graph after revocation.
 */
@Node("Credential")
data class CredentialNode(
    @Id
    val id: String,
    val userId: String,
    val authSettingId: String,
    val credentialType: String,
    /**
     * JSON-serialised [Credential.data] map. Values may be encrypted at rest;
     * see [Neo4jCredentialRepository] for the encryption/decryption lifecycle.
     */
    val dataJson: String? = null,
    // EntityMetadata fields
    val created: Instant,
    val createdBy: String? = null,
    val modified: Instant,
    val modifiedBy: String? = null,
) {
    fun toDomain(objectMapper: ObjectMapper): Credential {
        val rawData: Map<String, String> =
            dataJson?.let { objectMapper.readValue(it, DATA_TYPE) } ?: emptyMap()
        return Credential(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                ),
            userId = UUID.fromString(userId),
            authSettingId = UUID.fromString(authSettingId),
            credentialType = CredentialType.valueOf(credentialType),
            data = rawData,
        )
    }

    companion object {
        private val DATA_TYPE = object : TypeReference<Map<String, String>>() {}

        fun fromDomain(
            credential: Credential,
            objectMapper: ObjectMapper,
        ): CredentialNode =
            CredentialNode(
                id = credential.id.toString(),
                userId = credential.userId.toString(),
                authSettingId = credential.authSettingId.toString(),
                credentialType = credential.credentialType.name,
                dataJson = credential.data.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) },
                created = credential.metadata.created,
                createdBy = credential.metadata.createdBy,
                modified = credential.metadata.modified,
                modifiedBy = credential.metadata.modifiedBy,
            )
    }
}

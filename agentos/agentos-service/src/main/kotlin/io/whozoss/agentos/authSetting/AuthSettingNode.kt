package io.whozoss.agentos.authSetting

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.persistence.OverlayKeyEncoding
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.AuthType
import io.whozoss.agentos.sdk.encryption.FieldEncryptor
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [AuthSetting].
 *
 * Stored as `(:AuthSetting)-[:BELONGS_TO]->(:Namespace)` for namespace-scoped
 * settings. The [namespaceId] property keeps the scalar id for the
 * [findActiveByNamespaceId] query, while the [namespace] @Relationship is required
 * by the transitive permission Cypher queries. Both sources are kept in sync by
 * [Neo4jAuthSettingRepository.save].
 *
 * User-scoped settings (`userId != null`, `namespaceId == null`) do NOT get the
 * @Relationship — they are user-global and have no namespace to link to.
 *
 * [dataJson] stores the [AuthSetting.data] map serialised as JSON. Each value is
 * individually **encrypted** before write and **decrypted** after read using the
 * provided [FieldEncryptor]. Because [AuthSettingNode] is a plain data class (not a
 * Spring bean), the encryptor is passed explicitly to [toDomain] and [fromDomain].
 *
 * [authType] is stored as its enum name string and round-tripped via [AuthType.valueOf].
 */
@Node("AuthSetting")
data class AuthSettingNode(
    @Id
    val id: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val name: String,
    /**
     * Denormalised discriminator for the unique business triple `(namespaceId, userId,
     * name)`. Backed by a UNIQUE CONSTRAINT (cf. [AuthSettingSchemaInitializer]). Same
     * pattern as [io.whozoss.agentos.aiProvider.AiProviderNode.tripleKey] —
     * see [OverlayKeyEncoding] for rationale.
     *
     * Soft-deleted rows carry a per-id `tombstone:<uuid>` value so the unique slot is
     * freed for re-creation immediately after a delete.
     */
    val tripleKey: String,
    val description: String? = null,
    val authType: String,
    /**
     * JSON-serialised [AuthSetting.data] map. Each map value is encrypted at rest;
     * decryption happens in [toDomain] via the caller-supplied [FieldEncryptor].
     */
    val dataJson: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    val namespace: NamespaceNode? = null,
) {
    fun toDomain(encryptor: FieldEncryptor): AuthSetting {
        val rawData: Map<String, String> =
            dataJson?.let { MAPPER.readValue(it, DATA_TYPE) } ?: emptyMap()
        val decryptedData = rawData.mapValues { (_, v) -> encryptor.decrypt(v) }
        return AuthSetting(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            namespaceId = namespaceId?.let { UUID.fromString(it) },
            userId = userId?.let { UUID.fromString(it) },
            name = name,
            description = description,
            authType = AuthType.valueOf(authType),
            data = decryptedData,
        )
    }

    companion object {
        private val MAPPER = jacksonObjectMapper()
        private val DATA_TYPE = object : TypeReference<Map<String, String>>() {}

        fun computeTripleKey(
            namespaceId: UUID?,
            userId: UUID?,
            name: String,
        ): String = OverlayKeyEncoding.activeKey(namespaceId, userId, name)

        fun tombstoneTripleKey(id: String): String = OverlayKeyEncoding.tombstoneKey(id)

        fun fromDomain(
            config: AuthSetting,
            encryptor: FieldEncryptor,
        ): AuthSettingNode {
            val idString = config.id.toString()
            val tripleKey =
                when {
                    config.metadata.removed -> tombstoneTripleKey(idString)
                    else -> computeTripleKey(config.namespaceId, config.userId, config.name)
                }
            val encryptedData = config.data.mapValues { (_, v) -> encryptor.encrypt(v) }
            return AuthSettingNode(
                id = idString,
                namespaceId = config.namespaceId?.toString(),
                userId = config.userId?.toString(),
                name = config.name,
                tripleKey = tripleKey,
                description = config.description,
                authType = config.authType.name,
                dataJson = encryptedData.takeIf { it.isNotEmpty() }?.let { MAPPER.writeValueAsString(it) },
                created = config.metadata.created,
                createdBy = config.metadata.createdBy,
                modified = config.metadata.modified,
                modifiedBy = config.metadata.modifiedBy,
                removed = config.metadata.removed.takeIf { it },
            )
        }
    }
}

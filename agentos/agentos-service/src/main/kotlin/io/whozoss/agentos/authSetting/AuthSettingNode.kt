package io.whozoss.agentos.authSetting

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.persistence.OverlayKeyEncoding
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
 * [dataJson] stores the typed properties of each [AuthSetting] subtype serialised as a flat
 * JSON map. Conversion between the typed domain object and the flat map goes through
 * [AuthSetting.toDataMap] (write) and [authSettingFromDataMap] (read).
 *
 * **Encryption is not this class's responsibility.** [toDomain] and [fromDomain] only ever
 * see plaintext JSON in [dataJson]; encrypting/decrypting the serialised string as a whole
 * unit is applied by [Neo4jAuthSettingRepository] around the `save`/`find*` calls, the same
 * pattern used by [io.whozoss.agentos.credential.CredentialNode] /
 * [io.whozoss.agentos.credential.Neo4jCredentialRepository]. This keeps the node a pure data
 * holder with no dependency on [io.whozoss.agentos.encryption.FieldEncryptor].
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
     * JSON-serialised flat property map for the [AuthSetting] subtype. May be encrypted at
     * rest — see the class KDoc. [toDomain] / [fromDomain] always operate on plaintext.
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
    fun toDomain(): AuthSetting {
        val rawData: Map<String, String> =
            dataJson?.let { MAPPER.readValue(it, DATA_TYPE) } ?: emptyMap()
        return authSettingFromDataMap(
            authType = AuthType.valueOf(authType),
            data = rawData,
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

        fun fromDomain(config: AuthSetting): AuthSettingNode {
            val idString = config.id.toString()
            val tripleKey =
                when {
                    config.metadata.removed -> tombstoneTripleKey(idString)
                    else -> computeTripleKey(config.namespaceId, config.userId, config.name)
                }
            val dataMap = config.toDataMap()
            return AuthSettingNode(
                id = idString,
                namespaceId = config.namespaceId?.toString(),
                userId = config.userId?.toString(),
                name = config.name,
                tripleKey = tripleKey,
                description = config.description,
                authType = config.authType.name,
                dataJson = dataMap.takeIf { it.isNotEmpty() }?.let { MAPPER.writeValueAsString(it) },
                created = config.metadata.created,
                createdBy = config.metadata.createdBy,
                modified = config.metadata.modified,
                modifiedBy = config.metadata.modifiedBy,
                removed = config.metadata.removed.takeIf { it },
            )
        }
    }
}

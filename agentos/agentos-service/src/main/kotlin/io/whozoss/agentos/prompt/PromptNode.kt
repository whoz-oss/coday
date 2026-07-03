package io.whozoss.agentos.prompt

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.persistence.OverlayKeyEncoding
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Prompt].
 *
 * [contentJson] stores the ordered list of content strings as a JSON array because
 * Neo4j has no native ordered-list-of-strings type that round-trips cleanly through SDN.
 *
 * [parametersJson] stores the list of [PromptParameter] as a JSON array.
 * Null when the prompt has no parameters.
 *
 * For namespace-scoped prompts the BELONGS_TO edge to the parent Namespace is
 * materialised by [Neo4jPromptRepository.save] via Neo4jChildLinkService.
 * Platform-level prompts (namespaceId == null) have no BELONGS_TO edge.
 *
 * [fromDomain] accepts both active and removed entities — soft-deleted prompts
 * can be persisted via [save] to support frontend sync scenarios (e.g. cache
 * invalidation based on last-modified/removed state).
 *
 * [namespace] is a nullable var so SDN can call the primary constructor before
 * injecting the @Relationship field via property injection.
 *
 * [tripleKey] is a denormalised discriminator for the unique business triple
 * `(namespaceId, userId, name)`, backed by a UNIQUE CONSTRAINT. Computed via
 * [OverlayKeyEncoding.activeKey]; rewritten to a tombstone on soft-delete so the
 * unique slot is freed immediately for re-creation.
 *
 * [version] backs Spring Data Neo4j optimistic locking. A null version means the
 * entity has never been persisted (new entity); SDN sets it to 0 on first save.
 */
@Node("Prompt")
data class PromptNode(
    @Id val id: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val name: String,
    val description: String? = null,
    val contentJson: String,
    val parametersJson: String? = null,
    val tripleKey: String,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var namespace: NamespaceNode? = null,
) {
    fun toDomain(objectMapper: ObjectMapper): Prompt =
        Prompt(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                    version = version,
                ),
            namespaceId = namespaceId?.let { UUID.fromString(it) },
            userId = userId?.let { UUID.fromString(it) },
            name = name,
            description = description,
            content = objectMapper.readValue(contentJson, CONTENT_TYPE),
            parameters = parametersJson?.let { objectMapper.readValue(it, PARAMETERS_TYPE) } ?: emptyList(),
        )

    companion object {
        private val CONTENT_TYPE = object : TypeReference<List<String>>() {}
        private val PARAMETERS_TYPE = object : TypeReference<List<PromptParameter>>() {}

        fun computeTripleKey(namespaceId: UUID?, userId: UUID?, name: String): String =
            OverlayKeyEncoding.activeKey(namespaceId, userId, name)

        fun tombstoneTripleKey(id: String): String = OverlayKeyEncoding.tombstoneKey(id)

        fun fromDomain(
            prompt: Prompt,
            objectMapper: ObjectMapper,
        ): PromptNode {
            val idString = prompt.id.toString()
            return PromptNode(
                id = idString,
                namespaceId = prompt.namespaceId?.toString(),
                userId = prompt.userId?.toString(),
                name = prompt.name,
                description = prompt.description,
                contentJson = objectMapper.writeValueAsString(prompt.content),
                parametersJson = prompt.parameters.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) },
                tripleKey = computeTripleKey(prompt.namespaceId, prompt.userId, prompt.name),
                version = prompt.metadata.version,
                created = prompt.metadata.created,
                createdBy = prompt.metadata.createdBy,
                modified = prompt.metadata.modified,
                modifiedBy = prompt.metadata.modifiedBy,
                removed = prompt.metadata.removed.takeIf { it },
            )
        }
    }
}

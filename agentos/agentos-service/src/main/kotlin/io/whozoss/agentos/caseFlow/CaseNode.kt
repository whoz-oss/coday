package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Case].
 */
@Node("Case")
data class CaseNode(
    @Id
    val id: String,
    val namespaceId: String,
    val status: String,
    val title: String,
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
    /** JSON-serialised [Case.context] map. Null when no context was supplied at creation. */
    val contextJson: String? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var namespace: NamespaceNode? = null,
) {
    fun toDomain(objectMapper: ObjectMapper): Case =
        Case(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            namespaceId = UUID.fromString(namespaceId),
            status = CaseStatus.valueOf(status),
            title = title,
            context = contextJson?.let {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(it, Map::class.java) as Map<String, Any?>
            },
        )

    companion object {
        fun fromDomain(
            case: Case,
            objectMapper: ObjectMapper,
        ): CaseNode =
            CaseNode(
                id = case.id.toString(),
                namespaceId = case.namespaceId.toString(),
                status = case.status.name,
                title = case.title,
                created = case.metadata.created,
                createdBy = case.metadata.createdBy,
                modified = case.metadata.modified,
                modifiedBy = case.metadata.modifiedBy,
                removed = case.metadata.removed.takeIf { it },
                contextJson = case.context?.let { objectMapper.writeValueAsString(it) },
            )
    }
}

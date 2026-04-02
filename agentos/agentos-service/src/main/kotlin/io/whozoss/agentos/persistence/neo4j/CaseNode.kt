package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Property
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Case].
 *
 * Stored as a (:Case) node with a [namespaceId] property linking it to its parent
 * namespace. The relationship is represented as a property rather than an SDN
 * @Relationship to keep queries simple — full graph traversal between Namespace and
 * Case nodes is handled via Cypher in the repository when needed.
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
    @Property("removed")
    val removed: Boolean = false,
) {
    fun toDomain(): Case =
        Case(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed,
                ),
            namespaceId = UUID.fromString(namespaceId),
            status = CaseStatus.valueOf(status),
            title = title,
        )

    companion object {
        fun fromDomain(case: Case): CaseNode =
            CaseNode(
                id = case.id.toString(),
                namespaceId = case.namespaceId.toString(),
                status = case.status.name,
                title = case.title,
                created = case.metadata.created,
                createdBy = case.metadata.createdBy,
                modified = case.metadata.modified,
                modifiedBy = case.metadata.modifiedBy,
                removed = case.metadata.removed,
            )
    }
}

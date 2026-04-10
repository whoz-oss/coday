package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.caseFlow.Case
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
 *
 * Stored as a `(:Case)-[:BELONGS_TO]->(:Namespace)` edge.
 *
 * [namespace] is a nullable `var` so SDN can call the primary constructor before
 * injecting the @Relationship field. For SDN-generated queries the field is
 * populated automatically. For custom queries (via [Neo4jClient]) the caller
 * assembles the node directly with the correct [NamespaceNode] stub.
 *
 * [toDomain] reads [namespace]!!.id — a null here is a data-integrity error and
 * should surface as an NPE rather than be silently ignored.
 *
 * On write, [fromDomain] provides a stub [NamespaceNode] carrying only the `@Id`.
 * SDN issues a MERGE on the Namespace node by id and never overwrites its existing
 * properties, so saving a Case does not corrupt the Namespace.
 */
@Node("Case")
data class CaseNode(
    @Id
    val id: String,
    val status: String,
    val title: String,
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var namespace: NamespaceNode? = null,
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
                    removed = removed ?: false,
                ),
            namespaceId = UUID.fromString(namespace!!.id),
            status = CaseStatus.valueOf(status),
            title = title,
        )

    companion object {
        fun fromDomain(case: Case): CaseNode =
            CaseNode(
                id = case.id.toString(),
                status = case.status.name,
                title = case.title,
                created = case.metadata.created,
                createdBy = case.metadata.createdBy,
                modified = case.metadata.modified,
                modifiedBy = case.metadata.modifiedBy,
                removed = case.metadata.removed.takeIf { it },
                namespace = NamespaceNode.stub(case.namespaceId),
            )
    }
}

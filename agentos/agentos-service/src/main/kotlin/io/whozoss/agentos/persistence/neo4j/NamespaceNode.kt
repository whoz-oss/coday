package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Property
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Namespace].
 *
 * Stored as a (:Namespace) node. No parent relationship — namespaces are root-level.
 *
 * Properties kept flat (no nested objects) to avoid SDN's limited support for
 * embedded value types in Community Edition.
 */
@Node("Namespace")
data class NamespaceNode(
    @Id
    val id: String,
    val name: String,
    val description: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    @Property("removed")
    val removed: Boolean = false,
) {
    fun toDomain(): Namespace =
        Namespace(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed,
                ),
            name = name,
            description = description,
        )

    companion object {
        fun fromDomain(ns: Namespace): NamespaceNode =
            NamespaceNode(
                id = ns.id.toString(),
                name = ns.name,
                description = ns.description,
                created = ns.metadata.created,
                createdBy = ns.metadata.createdBy,
                modified = ns.metadata.modified,
                modifiedBy = ns.metadata.modifiedBy,
                removed = ns.metadata.removed,
            )
    }
}

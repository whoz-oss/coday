package io.whozoss.agentos.git

import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [RepositoryCheckout].
 *
 * [activeNamespaceKey] backs the "one active checkout per namespace" constraint. It carries the
 * namespace id while the row is active and `null` once it is soft-deleted: Neo4j property
 * uniqueness exempts nodes that lack the property, so clearing it is what frees the slot for a
 * later re-association. The plain [namespaceId] property is kept alongside it for ordinary reads,
 * which must keep working for removed rows.
 *
 * [namespace] is a nullable `var` so SDN can call the primary constructor before injecting the
 * @Relationship field. On write, [fromDomain] provides a stub carrying only the `@Id`; SDN MERGEs
 * by id and never overwrites existing Namespace properties.
 */
@Node("RepositoryCheckout")
data class RepositoryCheckoutNode(
    @Id
    val id: String,
    val namespaceId: String,
    val activeNamespaceKey: String? = null,
    val integrationConfigId: String,
    val repositoryUrl: String,
    val mainBranch: String,
    val status: String,
    val lastFetchedAt: Instant? = null,
    val failureReason: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Relationship(type = "BELONGS_TO", direction = OUTGOING)
    var namespace: NamespaceNode? = null,
) {
    fun toDomain(): RepositoryCheckout =
        RepositoryCheckout(
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
            integrationConfigId = UUID.fromString(integrationConfigId),
            repositoryUrl = repositoryUrl,
            mainBranch = mainBranch,
            status = RepositoryCheckoutStatus.valueOf(status),
            lastFetchedAt = lastFetchedAt,
            failureReason = failureReason,
        )

    companion object {
        fun fromDomain(checkout: RepositoryCheckout): RepositoryCheckoutNode =
            RepositoryCheckoutNode(
                id = checkout.id.toString(),
                namespaceId = checkout.namespaceId.toString(),
                activeNamespaceKey = checkout.namespaceId.toString().takeUnless { checkout.metadata.removed },
                integrationConfigId = checkout.integrationConfigId.toString(),
                repositoryUrl = checkout.repositoryUrl,
                mainBranch = checkout.mainBranch,
                status = checkout.status.name,
                lastFetchedAt = checkout.lastFetchedAt,
                failureReason = checkout.failureReason,
                created = checkout.metadata.created,
                createdBy = checkout.metadata.createdBy,
                modified = checkout.metadata.modified,
                modifiedBy = checkout.metadata.modifiedBy,
                removed = checkout.metadata.removed.takeIf { it },
            )
    }
}

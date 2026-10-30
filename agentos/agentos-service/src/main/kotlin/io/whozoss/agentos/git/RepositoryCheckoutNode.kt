package io.whozoss.agentos.git

import io.whozoss.agentos.namespace.NamespaceNode
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
 * Spring Data Neo4j projection for [RepositoryCheckout].
 *
 * An active row also carries the `ActiveRepositoryCheckout` label, set and removed by
 * [Neo4jRepositoryCheckoutRepository]. The "one active checkout per namespace" constraint is
 * declared on that label, like `ActiveNamespace`, so soft deletion frees the slot by removing it.
 *
 * [namespace] is a nullable `var` so SDN can call the primary constructor before injecting the
 * @Relationship field. On write, [fromDomain] provides a stub carrying only the `@Id`; SDN MERGEs
 * by id and never overwrites existing Namespace properties.
 *
 * [version] carries optimistic locking and tells Spring Data whether a save creates the row, so the
 * creation fields are audited only once.
 */
@Node("RepositoryCheckout")
data class RepositoryCheckoutNode(
    @Id
    val id: String,
    val namespaceId: String,
    val integrationConfigId: String,
    val repositoryUrl: String,
    val mainBranch: String,
    val status: String,
    val lastFetchedAt: Instant? = null,
    val failureReason: String? = null,
    // EntityMetadata fields
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
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
                    version = version,
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
                integrationConfigId = checkout.integrationConfigId.toString(),
                repositoryUrl = checkout.repositoryUrl,
                mainBranch = checkout.mainBranch,
                status = checkout.status.name,
                lastFetchedAt = checkout.lastFetchedAt,
                failureReason = checkout.failureReason,
                version = checkout.metadata.version,
                created = checkout.metadata.created,
                createdBy = checkout.metadata.createdBy,
                modified = checkout.metadata.modified,
                modifiedBy = checkout.metadata.modifiedBy,
                removed = checkout.metadata.removed.takeIf { it },
            )
    }
}

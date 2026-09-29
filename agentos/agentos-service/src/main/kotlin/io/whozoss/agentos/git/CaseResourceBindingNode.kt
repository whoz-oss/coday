package io.whozoss.agentos.git

import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [CaseResourceBinding].
 *
 * An active row also carries the `ActiveCaseResourceBinding` label, set and removed by
 * [Neo4jCaseResourceBindingRepository]. The "one active binding per root case" constraint is
 * declared on that label, like `ActiveNamespace`, so soft deletion frees the slot by removing it.
 *
 * No `BELONGS_TO` edge is materialised: a binding is reached by its root case id, and adding a
 * second edge into the case graph would make the family-enumeration queries ambiguous.
 *
 * Only the modification fields are audited. The id is assigned by the application, so without a
 * `@Version` Spring Data treats every save as new and `@CreatedDate` would reset [created].
 */
@Node("CaseResourceBinding")
data class CaseResourceBindingNode(
    @Id
    val id: String,
    val rootCaseId: String,
    val namespaceId: String,
    val integrationConfigId: String,
    val status: String,
    val failureReason: String? = null,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): CaseResourceBinding =
        CaseResourceBinding(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                ),
            rootCaseId = UUID.fromString(rootCaseId),
            namespaceId = UUID.fromString(namespaceId),
            integrationConfigId = UUID.fromString(integrationConfigId),
            status = CaseResourceStatus.valueOf(status),
            failureReason = failureReason,
        )

    companion object {
        fun fromDomain(binding: CaseResourceBinding): CaseResourceBindingNode =
            CaseResourceBindingNode(
                id = binding.id.toString(),
                rootCaseId = binding.rootCaseId.toString(),
                namespaceId = binding.namespaceId.toString(),
                integrationConfigId = binding.integrationConfigId.toString(),
                status = binding.status.name,
                failureReason = binding.failureReason,
                created = binding.metadata.created,
                createdBy = binding.metadata.createdBy,
                modified = binding.metadata.modified,
                modifiedBy = binding.metadata.modifiedBy,
                removed = binding.metadata.removed.takeIf { it },
            )
    }
}

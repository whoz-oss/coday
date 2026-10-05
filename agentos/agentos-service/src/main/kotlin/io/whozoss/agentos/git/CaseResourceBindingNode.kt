package io.whozoss.agentos.git

import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
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
 * [version] carries optimistic locking and tells Spring Data whether a save creates the row, so the
 * creation fields are audited only once.
 */
@Node("CaseResourceBinding")
data class CaseResourceBindingNode(
    @Id
    val id: String,
    val rootCaseId: String,
    val namespaceId: String,
    val integrationConfigId: String,
    val status: String,
    val baseSha: String? = null,
    val failureReason: String? = null,
    val settingsJson: String? = null,
    val cleanupReason: String? = null,
    val setupStarted: Boolean = false,
    val setupCompleted: Boolean = false,
    // EntityMetadata fields
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
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
                    version = version,
                ),
            rootCaseId = UUID.fromString(rootCaseId),
            namespaceId = UUID.fromString(namespaceId),
            integrationConfigId = UUID.fromString(integrationConfigId),
            status = CaseResourceStatus.valueOf(status),
            baseSha = baseSha,
            failureReason = failureReason,
            settingsJson = settingsJson,
            cleanupReason = cleanupReason,
            setupStarted = setupStarted,
            setupCompleted = setupCompleted,

        )

    companion object {
        fun fromDomain(binding: CaseResourceBinding): CaseResourceBindingNode =
            CaseResourceBindingNode(
                id = binding.id.toString(),
                rootCaseId = binding.rootCaseId.toString(),
                namespaceId = binding.namespaceId.toString(),
                integrationConfigId = binding.integrationConfigId.toString(),
                status = binding.status.name,
                baseSha = binding.baseSha,
                failureReason = binding.failureReason,
                settingsJson = binding.settingsJson,
                cleanupReason = binding.cleanupReason,
                setupStarted = binding.setupStarted,
                setupCompleted = binding.setupCompleted,
                version = binding.metadata.version,
                created = binding.metadata.created,
                createdBy = binding.metadata.createdBy,
                modified = binding.metadata.modified,
                modifiedBy = binding.metadata.modifiedBy,
                removed = binding.metadata.removed.takeIf { it },
            )
    }
}

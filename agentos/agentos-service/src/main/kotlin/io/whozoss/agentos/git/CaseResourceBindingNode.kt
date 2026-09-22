package io.whozoss.agentos.git

import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [CaseResourceBinding].
 *
 * [activeRootCaseKey] backs the "one active binding per root case" constraint, carrying the root
 * case id while active and `null` once soft-deleted, since Neo4j property uniqueness exempts nodes
 * without the property.
 *
 * No `BELONGS_TO` edge is materialised: a binding is reached by its root case id, and adding a
 * second edge into the case graph would make the family-enumeration queries ambiguous.
 */
@Node("CaseResourceBinding")
data class CaseResourceBindingNode(
    @Id
    val id: String,
    val rootCaseId: String,
    val activeRootCaseKey: String? = null,
    val namespaceId: String,
    val integrationConfigId: String,
    val status: String,
    val branchName: String? = null,
    val baseSha: String? = null,
    val failureReason: String? = null,
    val settingsJson: String? = null,
    val summaryJson: String? = null,
    val cleanupReason: String? = null,
    val setupStarted: Boolean = false,
    val setupCompleted: Boolean = false,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
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
            branchName = branchName,
            baseSha = baseSha,
            failureReason = failureReason,
            settingsJson = settingsJson,
            summaryJson = summaryJson,
            cleanupReason = cleanupReason,
            setupStarted = setupStarted,
            setupCompleted = setupCompleted,

        )

    companion object {
        fun fromDomain(binding: CaseResourceBinding): CaseResourceBindingNode =
            CaseResourceBindingNode(
                id = binding.id.toString(),
                rootCaseId = binding.rootCaseId.toString(),
                activeRootCaseKey = binding.rootCaseId.toString().takeUnless { binding.metadata.removed },
                namespaceId = binding.namespaceId.toString(),
                integrationConfigId = binding.integrationConfigId.toString(),
                status = binding.status.name,
                branchName = binding.branchName,
                baseSha = binding.baseSha,
                failureReason = binding.failureReason,
                settingsJson = binding.settingsJson,
                summaryJson = binding.summaryJson,
                cleanupReason = binding.cleanupReason,
                setupStarted = binding.setupStarted,
                setupCompleted = binding.setupCompleted,

                created = binding.metadata.created,
                createdBy = binding.metadata.createdBy,
                modified = binding.metadata.modified,
                modifiedBy = binding.metadata.modifiedBy,
                removed = binding.metadata.removed.takeIf { it },
            )
    }
}

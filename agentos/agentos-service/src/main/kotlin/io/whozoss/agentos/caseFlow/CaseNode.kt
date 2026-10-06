package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Case].
 *
 * [version] carries optimistic locking and tells Spring Data whether a save creates the node: a
 * save based on a stale read is refused, and so is the creation of a case whose id already exists.
 */
@Node("Case")
data class CaseNode(
    @Id
    val id: String,
    val namespaceId: String,
    val status: String,
    val title: String,
    val parentCaseId: String? = null,
    val scheduledPromptId: String? = null,
    val runCostThreshold: Double? = null,
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
    @Version val version: Long? = null,
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
                    version = version,
                ),
            namespaceId = UUID.fromString(namespaceId),
            status = CaseStatus.valueOf(status),
            title = title,
            parentCaseId = parentCaseId?.let { UUID.fromString(it) },
            scheduledPromptId = scheduledPromptId?.let { UUID.fromString(it) },
            runCostThreshold = runCostThreshold,
        )

    companion object {
        fun fromDomain(case: Case): CaseNode =
            CaseNode(
                id = case.id.toString(),
                namespaceId = case.namespaceId.toString(),
                status = case.status.name,
                title = case.title,
                parentCaseId = case.parentCaseId?.toString(),
                scheduledPromptId = case.scheduledPromptId?.toString(),
                runCostThreshold = case.runCostThreshold,
                created = case.metadata.created,
                createdBy = case.metadata.createdBy,
                modified = case.metadata.modified,
                modifiedBy = case.metadata.modifiedBy,
                removed = case.metadata.removed.takeIf { it },
                version = case.metadata.version,
            )
    }
}

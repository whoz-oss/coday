package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.namespace.NamespaceNode
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
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
 * [reasoningEffort] is stored as its enum name string and round-tripped via [ReasoningEffort.valueOf].
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
    val originCaseId: String? = null,
    val runCostThreshold: Double? = null,
    val reasoningEffort: String? = null,
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
            namespaceId = UUID.fromString(namespaceId),
            status = CaseStatus.valueOf(status),
            title = title,
            parentCaseId = parentCaseId?.let { UUID.fromString(it) },
            scheduledPromptId = scheduledPromptId?.let { UUID.fromString(it) },
            originCaseId = originCaseId?.let { UUID.fromString(it) },
            runCostThreshold = runCostThreshold,
            reasoningEffort = reasoningEffort?.let(ReasoningEffort::valueOf),
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
                originCaseId = case.originCaseId?.toString(),
                runCostThreshold = case.runCostThreshold,
                reasoningEffort = case.reasoningEffort?.name,
                created = case.metadata.created,
                createdBy = case.metadata.createdBy,
                modified = case.metadata.modified,
                modifiedBy = case.metadata.modifiedBy,
                removed = case.metadata.removed.takeIf { it },
            )
    }
}

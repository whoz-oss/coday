package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of a `human_interactions` row.
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, interactionId)`
 * encoded as a single string. [revision] is the optimistic-locking counter,
 * compared-and-swapped by an explicit Cypher statement. The interaction payload
 * JSON stays as raw text.
 */
@Node("HumanInteraction")
data class HumanInteractionNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val interactionId: String,
    val stepId: String,
    val interactionType: String,
    val status: String,
    val revision: Int,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(objectMapper: ObjectMapper): HumanInteractionRecord = HumanInteractionRecord(
        interactionId = interactionId,
        namespaceId = namespaceId,
        workflowId = workflowId,
        stepId = stepId,
        interactionType = interactionType,
        status = status,
        revision = revision,
        payload = objectMapper.readJsonMap(payload),
    )

    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
            interactionId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$interactionId"

        fun fromDomain(
            scope: TenantScope,
            record: HumanInteractionRecord,
            objectMapper: ObjectMapper,
        ): HumanInteractionNode =
            HumanInteractionNode(
                id = compositeId(
                    scope.organizationId,
                    scope.workstreamId,
                    record.namespaceId,
                    record.workflowId,
                    record.interactionId,
                ),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = record.namespaceId,
                workflowId = record.workflowId,
                interactionId = record.interactionId,
                stepId = record.stepId,
                interactionType = record.interactionType,
                status = record.status,
                revision = record.revision,
                payload = objectMapper.writeJson(record.payload),
            )
    }
}

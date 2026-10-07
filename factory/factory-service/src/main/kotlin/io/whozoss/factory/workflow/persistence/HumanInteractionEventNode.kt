package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of an append-only `human_interaction_events` row.
 *
 * The node [id] is the composite business key
 * `(organizationId, workstreamId, namespaceId, workflowId, interactionId,
 * eventId)` encoded as a single string; the insert is idempotent by
 * construction. [createdAt] gives the journal its ordering.
 */
@Node("HumanInteractionEvent")
data class HumanInteractionEventNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val namespaceId: String,
    val workflowId: String,
    val interactionId: String,
    val eventId: String,
    val eventType: String,
    val actorId: String,
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
) {
    fun toDomain(objectMapper: ObjectMapper): HumanInteractionEventRecord = HumanInteractionEventRecord(
        eventId = eventId,
        interactionId = interactionId,
        eventType = eventType,
        actorId = actorId,
        payload = objectMapper.readJsonMap(payload),
    )

    companion object {
        fun compositeId(
            organizationId: String,
            workstreamId: String,
            namespaceId: String,
            workflowId: String,
            interactionId: String,
            eventId: String,
        ): String = "$organizationId|$workstreamId|$namespaceId|$workflowId|$interactionId|$eventId"

        fun fromDomain(
            scope: TenantScope,
            namespaceId: String,
            workflowId: String,
            event: HumanInteractionEventRecord,
            objectMapper: ObjectMapper,
        ): HumanInteractionEventNode = HumanInteractionEventNode(
            id = compositeId(
                scope.organizationId,
                scope.workstreamId,
                namespaceId,
                workflowId,
                event.interactionId,
                event.eventId,
            ),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = namespaceId,
            workflowId = workflowId,
            interactionId = event.interactionId,
            eventId = event.eventId,
            eventType = event.eventType,
            actorId = event.actorId,
            payload = objectMapper.writeJson(event.payload),
        )
    }
}

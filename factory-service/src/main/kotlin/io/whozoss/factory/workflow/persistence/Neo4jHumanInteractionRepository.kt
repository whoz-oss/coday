package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.HumanInteractionEventRecord
import io.whozoss.factory.workflow.domain.HumanInteractionRecord
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Neo4j implementation of [HumanInteractionRepository].
 *
 * Replaces the retired `JdbcWorkflowRepository`'s interaction surface. The
 * interaction is one `:HumanInteraction` node whose `revision` is
 * compare-and-swapped on update; its append-only lifecycle journal is the
 * `:HumanInteractionEvent` label.
 */
@Repository
@Primary
class Neo4jHumanInteractionRepository(
    private val interactions: SpringDataNeo4jHumanInteractionRepository,
    private val events: SpringDataNeo4jHumanInteractionEventRepository,
    private val objectMapper: ObjectMapper,
) : HumanInteractionRepository {

    override fun find(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
    ): HumanInteractionRecord? = read(scope, namespaceId, workflowId, interactionId)

    override fun list(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        openOnly: Boolean,
    ): List<HumanInteractionRecord> =
        interactions
            .findAllByInstance(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
            .asSequence()
            .filter { !openOnly || it.status == "waiting" }
            .map { it.toDomain(objectMapper) }
            .toList()

    override fun insert(scope: TenantScope, record: HumanInteractionRecord): HumanInteractionRecord {
        val id = interactionId(scope, record.namespaceId, record.workflowId, record.interactionId)
        val existing = interactions.findById(id).orElse(null)
        if (existing != null) return existing.toDomain(objectMapper)
        interactions.save(HumanInteractionNode.fromDomain(scope, record, objectMapper))
        return read(scope, record.namespaceId, record.workflowId, record.interactionId) ?: record
    }

    override fun update(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
        expectedRevision: Int,
        next: HumanInteractionRecord,
    ): Boolean =
        interactions.casUpdate(
            id = interactionId(scope, namespaceId, workflowId, interactionId),
            expectedRevision = expectedRevision,
            status = next.status,
            revision = next.revision,
            payload = objectMapper.writeJson(next.payload),
            updatedAt = Instant.now(),
        ) > 0

    override fun appendEvent(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        event: HumanInteractionEventRecord,
    ) {
        val id = HumanInteractionEventNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            workflowId,
            event.interactionId,
            event.eventId,
        )
        if (events.existsById(id)) return
        events.save(HumanInteractionEventNode.fromDomain(scope, namespaceId, workflowId, event, objectMapper))
    }

    override fun listEvents(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): List<HumanInteractionEventRecord> =
        events
            .findAllByInstance(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
            .map { it.toDomain(objectMapper) }

    private fun read(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
    ): HumanInteractionRecord? =
        interactions
            .findById(interactionId(scope, namespaceId, workflowId, interactionId))
            .orElse(null)
            ?.toDomain(objectMapper)

    private fun interactionId(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        interactionId: String,
    ): String =
        HumanInteractionNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            workflowId,
            interactionId,
        )
}

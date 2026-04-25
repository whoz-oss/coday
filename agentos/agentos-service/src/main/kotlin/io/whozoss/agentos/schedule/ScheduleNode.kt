package io.whozoss.agentos.schedule

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Schedule].
 *
 * All fields are flat (no nested objects) to avoid SDN Community Edition
 * limitations with embedded value types.
 *
 * [intervalScheduleJson] stores the [IntervalSchedule] value object as a raw
 * JSON string.  Serialisation/deserialisation is performed by
 * [Neo4jScheduleRepository] using its injected [ObjectMapper], keeping the
 * node class itself free of Jackson dependencies.
 *
 * [toDomain] requires a pre-deserialised [IntervalSchedule] parameter rather
 * than an [ObjectMapper] so the node class stays a pure data holder.
 */
@Node("Schedule")
data class ScheduleNode(
    @Id
    val id: String,
    val namespaceId: String,
    val caseId: String? = null,
    val agentName: String? = null,
    val message: String,
    val enabled: Boolean = true,
    val oneShot: Boolean = false,
    val triggerAt: Instant? = null,
    val intervalScheduleJson: String? = null,
    val nextTriggerAt: Instant? = null,
    val lastTriggeredAt: Instant? = null,
    val occurrenceCount: Int = 0,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(intervalSchedule: IntervalSchedule?): Schedule =
        Schedule(
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
            caseId = caseId?.let { UUID.fromString(it) },
            agentName = agentName,
            message = message,
            enabled = enabled,
            oneShot = oneShot,
            triggerAt = triggerAt,
            intervalSchedule = intervalSchedule,
            nextTriggerAt = nextTriggerAt,
            lastTriggeredAt = lastTriggeredAt,
            occurrenceCount = occurrenceCount,
        )

    companion object {
        fun fromDomain(
            schedule: Schedule,
            objectMapper: ObjectMapper,
        ): ScheduleNode =
            ScheduleNode(
                id = schedule.id.toString(),
                namespaceId = schedule.namespaceId.toString(),
                caseId = schedule.caseId?.toString(),
                agentName = schedule.agentName,
                message = schedule.message,
                enabled = schedule.enabled,
                oneShot = schedule.oneShot,
                triggerAt = schedule.triggerAt,
                intervalScheduleJson = schedule.intervalSchedule?.let { objectMapper.writeValueAsString(it) },
                nextTriggerAt = schedule.nextTriggerAt,
                lastTriggeredAt = schedule.lastTriggeredAt,
                occurrenceCount = schedule.occurrenceCount,
                created = schedule.metadata.created,
                createdBy = schedule.metadata.createdBy,
                modified = schedule.metadata.modified,
                modifiedBy = schedule.metadata.modifiedBy,
                removed = schedule.metadata.removed.takeIf { it },
            )
    }
}

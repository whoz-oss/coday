package io.whozoss.agentos.scheduledPrompt

import io.whozoss.agentos.persistence.OverlayKeyEncoding
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Spring Data Neo4j projection for [ScheduledPrompt].
 *
 * SDN does not support nested data-class properties, so [Recurrence] and [Planning]
 * are **flattened** into scalar / list fields on this node class.
 * [toDomain] reconstructs the nested objects; [fromDomain] flattens them back.
 *
 * ### Stored as strings
 *
 * [unit] and [endType] are stored as the enum name (e.g. "DAY", "NEVER").
 * [days] is stored as a list of [DayOfWeek] names (e.g. ["MONDAY", "FRIDAY"]).
 * [timeUtc] is stored as [LocalTime] (SDN converts to ISO-8601 "HH:mm:ss" string).
 * [startDate] and [endDate] are stored as [LocalDate] (SDN converts to ISO-8601).
 *
 * ### Soft-delete
 *
 * [removed] is null for active, true for soft-deleted.
 * Always filter with `WHERE NOT COALESCE(removed, false)`.
 *
 * ### tripleKey
 *
 * Denormalised discriminator for the (namespaceId, userId, name) UNIQUE constraint.
 */
@Node("ScheduledPrompt")
data class ScheduledPromptNode(
    @Id val id: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val agentConfigId: String,
    val promptTemplateId: String,
    val name: String,
    val description: String? = null,
    // --- Recurrence fields (flattened) ---
    val every: Int,
    val unit: String,
    val days: List<String> = emptyList(),
    val timeUtc: LocalTime,
    // --- Planning fields (flattened) ---
    val startDate: LocalDate,
    val endType: String,
    val endDate: LocalDate? = null,
    val occurrenceCount: Int? = null,
    // --- Common ---
    val enabled: Boolean = true,
    val nextRunAt: Instant,
    val lastRunAt: Instant? = null,
    val tripleKey: String,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): ScheduledPrompt =
        ScheduledPrompt(
            metadata = EntityMetadata(
                id = UUID.fromString(id),
                created = created,
                createdBy = createdBy,
                modified = modified,
                modifiedBy = modifiedBy,
                removed = removed ?: false,
                version = version,
            ),
            namespaceId = namespaceId?.let { UUID.fromString(it) },
            userId = userId?.let { UUID.fromString(it) },
            agentConfigId = UUID.fromString(agentConfigId),
            promptTemplateId = UUID.fromString(promptTemplateId),
            name = name,
            description = description,
            recurrence = Recurrence(
                every = every,
                unit = SchedulerUnit.valueOf(unit),
                days = days.map { DayOfWeek.valueOf(it) },
                timeUtc = timeUtc,
            ),
            planning = Planning(
                startDate = startDate,
                endType = SchedulerEndType.valueOf(endType),
                endDate = endDate,
                occurrenceCount = occurrenceCount,
            ),
            enabled = enabled,
            nextRunAt = nextRunAt,
            lastRunAt = lastRunAt,
        )

    companion object {
        fun computeTripleKey(namespaceId: UUID?, userId: UUID?, name: String): String =
            OverlayKeyEncoding.activeKey(namespaceId, userId, name)

        fun tombstoneTripleKey(id: String): String = OverlayKeyEncoding.tombstoneKey(id)

        fun fromDomain(sp: ScheduledPrompt): ScheduledPromptNode =
            ScheduledPromptNode(
                id = sp.id.toString(),
                namespaceId = sp.namespaceId?.toString(),
                userId = sp.userId?.toString(),
                agentConfigId = sp.agentConfigId.toString(),
                promptTemplateId = sp.promptTemplateId.toString(),
                name = sp.name,
                description = sp.description,
                every = sp.recurrence.every,
                unit = sp.recurrence.unit.name,
                days = sp.recurrence.days.map { it.name },
                timeUtc = sp.recurrence.timeUtc,
                startDate = sp.planning.startDate,
                endType = sp.planning.endType.name,
                endDate = sp.planning.endDate,
                occurrenceCount = sp.planning.occurrenceCount,
                enabled = sp.enabled,
                nextRunAt = sp.nextRunAt,
                lastRunAt = sp.lastRunAt,
                tripleKey = computeTripleKey(sp.namespaceId, sp.userId, sp.name),
                version = sp.metadata.version,
                created = sp.metadata.created,
                createdBy = sp.metadata.createdBy,
                modified = sp.metadata.modified,
                modifiedBy = sp.metadata.modifiedBy,
                removed = sp.metadata.removed.takeIf { it },
            )
    }
}

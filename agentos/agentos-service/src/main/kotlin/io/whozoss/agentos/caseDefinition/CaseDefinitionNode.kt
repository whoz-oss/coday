package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.persistence.OverlayKeyEncoding
import io.whozoss.agentos.sdk.api.caseDefinition.SchedulerEndType
import io.whozoss.agentos.sdk.api.caseDefinition.SchedulerUnit
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
 * Spring Data Neo4j projection for [CaseDefinition].
 *
 * SDN does not support nested data-class properties, so [Recurrence] and [Planning]
 * are **flattened** into scalar / list fields on this node class.
 * [toDomain] reconstructs the nested objects; [fromDomain] flattens them back.
 *
 * ### Stored as strings
 *
 * [unit] and [endType] are stored as the enum name (e.g. "DAY", "NEVER").
 * [days] is stored as a list of [DayOfWeek] names (e.g. ["MONDAY", "FRIDAY"]).
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
@Node("CaseDefinition")
data class CaseDefinitionNode(
    @Id val id: String,
    val namespaceId: String? = null,
    val userId: String? = null,
    val agentConfigId: String,
    val promptId: String,
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
    val tripleKey: String,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): CaseDefinition =
        CaseDefinition(
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
            promptId = UUID.fromString(promptId),
            name = name,
            description = description,
            recurrence = Recurrence(
                every = every,
                unit = SchedulerUnit.valueOf(unit),
                days = days.map { DayOfWeek.valueOf(it) },
                timeUtc = timeUtc,  // LocalTime stored natively by SDN
            ),
            planning = Planning(
                startDate = startDate,
                endType = SchedulerEndType.valueOf(endType),
                endDate = endDate,
                occurrenceCount = occurrenceCount,
            ),
            enabled = enabled,
        )

    companion object {
        fun computeTripleKey(namespaceId: UUID?, userId: UUID?, name: String): String =
            OverlayKeyEncoding.activeKey(namespaceId, userId, name)

        fun tombstoneTripleKey(id: String): String = OverlayKeyEncoding.tombstoneKey(id)

        fun fromDomain(def: CaseDefinition): CaseDefinitionNode =
            CaseDefinitionNode(
                id = def.id.toString(),
                namespaceId = def.namespaceId?.toString(),
                userId = def.userId?.toString(),
                agentConfigId = def.agentConfigId.toString(),
                promptId = def.promptId.toString(),
                name = def.name,
                description = def.description,
                // Recurrence flattened
                every = def.recurrence.every,
                unit = def.recurrence.unit.name,
                days = def.recurrence.days.map { it.name },
                timeUtc = def.recurrence.timeUtc,  // LocalTime stored natively by SDN
                // Planning flattened
                startDate = def.planning.startDate,
                endType = def.planning.endType.name,
                endDate = def.planning.endDate,
                occurrenceCount = def.planning.occurrenceCount,
                enabled = def.enabled,
                tripleKey = computeTripleKey(def.namespaceId, def.userId, def.name),
                version = def.metadata.version,
                created = def.metadata.created,
                createdBy = def.metadata.createdBy,
                modified = def.metadata.modified,
                modifiedBy = def.metadata.modifiedBy,
                removed = def.metadata.removed.takeIf { it },
            )
    }
}

package io.whozoss.agentos.scheduledPrompt

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
 * Spring Data Neo4j projection for [ScheduledPromptRun].
 *
 * ### slotKey
 *
 * Denormalised unique discriminator: `"$scheduledPromptId|$scheduledForEpochMilli"`. The UNIQUE
 * constraint on [slotKey] acts as the distributed lock preventing double-firing for the same slot.
 * Inserting a duplicate throws a [DuplicateRunException] in [Neo4jScheduledPromptRunRepository].
 *
 * ### Soft-delete
 *
 * Runs are never soft-deleted — they are immutable audit records. [removed] is kept for
 * structural consistency with the entity framework but is always null.
 */
@Node("ScheduledPromptRun")
data class ScheduledPromptRunNode(
    @Id val id: String,
    val scheduledPromptId: String,
    val scheduledFor: Instant,
    val status: String,
    val correlationId: String,
    val attempt: Int = 0,
    val finishedAt: Instant? = null,
    val error: String? = null,
    /** Unique slot key: "$scheduledPromptId|$scheduledForEpochMilli" */
    val slotKey: String,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): ScheduledPromptRun =
        ScheduledPromptRun(
            metadata = EntityMetadata(
                id = UUID.fromString(id),
                created = created,
                createdBy = createdBy,
                modified = modified,
                modifiedBy = modifiedBy,
                removed = removed ?: false,
                version = version,
            ),
            scheduledPromptId = UUID.fromString(scheduledPromptId),
            scheduledFor = scheduledFor,
            status = RunStatus.valueOf(status),
            correlationId = correlationId,
            attempt = attempt,
            finishedAt = finishedAt,
            error = error,
        )

    companion object {
        fun slotKey(scheduledPromptId: UUID, scheduledFor: Instant): String =
            "$scheduledPromptId|${scheduledFor.toEpochMilli()}"

        fun fromDomain(run: ScheduledPromptRun): ScheduledPromptRunNode =
            ScheduledPromptRunNode(
                id = run.id.toString(),
                scheduledPromptId = run.scheduledPromptId.toString(),
                scheduledFor = run.scheduledFor,
                status = run.status.name,
                correlationId = run.correlationId,
                attempt = run.attempt,
                finishedAt = run.finishedAt,
                error = run.error,
                slotKey = slotKey(run.scheduledPromptId, run.scheduledFor),
                version = run.metadata.version,
                created = run.metadata.created,
                createdBy = run.metadata.createdBy,
                modified = run.metadata.modified,
                modifiedBy = run.metadata.modifiedBy,
                removed = run.metadata.removed.takeIf { it },
            )
    }
}

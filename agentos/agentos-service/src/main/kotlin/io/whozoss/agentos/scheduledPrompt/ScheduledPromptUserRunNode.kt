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
 * Spring Data Neo4j projection for [ScheduledPromptUserRun].
 *
 * ### userRunKey
 *
 * Denormalised unique discriminator: `"$runId|$userId"`. The UNIQUE constraint on [userRunKey]
 * acts as the distributed lock preventing duplicate per-user launches within the same Run.
 * The [materialize][ScheduledPromptUserRunNodeNeo4jRepository.materialize] Cypher uses MERGE,
 * so duplicates are silently absorbed — no exception is thrown.
 *
 * ### leaseUntil
 *
 * Populated when [status] = `"RUNNING"`. The next tick's [findClaimable][ScheduledPromptUserRunNodeNeo4jRepository.findClaimable]
 * query will pick up entries whose lease has expired, providing crash recovery without a
 * separate ApplicationRunner.
 *
 * ### Soft-delete
 *
 * UserRuns are never soft-deleted — they are immutable audit records. [removed] is kept for
 * structural consistency with the entity framework but is always null.
 */
@Node("ScheduledPromptUserRun")
data class ScheduledPromptUserRunNode(
    @Id val id: String,
    val runId: String,
    val userId: String,
    val status: String,
    val error: String? = null,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** Lease expiry for RUNNING status; null for non-RUNNING entries. */
    val leaseUntil: Instant? = null,
    /** UNIQUE constraint key: "$runId|$userId". */
    val userRunKey: String,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): ScheduledPromptUserRun =
        ScheduledPromptUserRun(
            metadata = EntityMetadata(
                id = UUID.fromString(id),
                created = created,
                createdBy = createdBy,
                modified = modified,
                modifiedBy = modifiedBy,
                removed = removed ?: false,
                version = version,
            ),
            runId = UUID.fromString(runId),
            userId = UUID.fromString(userId),
            status = UserRunStatus.valueOf(status),
            error = error,
            startedAt = startedAt,
            finishedAt = finishedAt,
            leaseUntil = leaseUntil,
        )

    companion object {
        fun userRunKey(runId: UUID, userId: UUID): String = "$runId|$userId"

        fun fromDomain(userRun: ScheduledPromptUserRun): ScheduledPromptUserRunNode =
            ScheduledPromptUserRunNode(
                id = userRun.id.toString(),
                runId = userRun.runId.toString(),
                userId = userRun.userId.toString(),
                status = userRun.status.name,
                error = userRun.error,
                startedAt = userRun.startedAt,
                finishedAt = userRun.finishedAt,
                leaseUntil = userRun.leaseUntil,
                userRunKey = userRunKey(userRun.runId, userRun.userId),
                version = userRun.metadata.version,
                created = userRun.metadata.created,
                createdBy = userRun.metadata.createdBy,
                modified = userRun.metadata.modified,
                modifiedBy = userRun.metadata.modifiedBy,
                removed = userRun.metadata.removed.takeIf { it },
            )
    }
}

package io.whozoss.agentos.casePlugin

import java.util.UUID

/**
 * Composes the idempotency key carried by [CaseStarterRequest.dispatchKey] and stored on
 * [io.whozoss.agentos.caseFlow.Case.dispatchKey].
 *
 * A dispatch key names **one intended delivery**. Two starts carrying the same key are the same
 * delivery attempted twice, and the second is refused with
 * [CaseStarterRefusal.ALREADY_DISPATCHED] rather than creating a duplicate case.
 *
 * This is what makes a fan-out **safely re-runnable**. The converged scheduler creates cases
 * directly and synchronously, so there is no per-user work queue to resume from: a run that dies
 * halfway is retried as a whole. Without a key that would re-deliver to everyone already reached.
 * With one, a re-run is effectively-once at element granularity — bought with a uniqueness
 * constraint instead of a queue and a lease.
 *
 * The concept is deliberately **generic**: an idempotency key for case creation, not a scheduler
 * concept. [io.whozoss.agentos.casePlugin.CaseStarterService] never learns what produced it.
 * The scheduler's composition happens to be `runId:userId[:entityId]`, but any caller able to name
 * its own deliveries stably may use its own.
 *
 * ### Granularity
 *
 * [entityId] is what makes one case **per entity** rather than per user. A manager reached by three
 * incomplete talent profiles has three distinct keys, hence three cases, each independently
 * idempotent. Omitting it collapses the delivery to one per user per run — the right shape when
 * the preceding step produced users rather than entities.
 *
 * ### Soft-delete
 *
 * The backing UNIQUE constraint still binds a soft-deleted node, so a deleted case would otherwise
 * hold its key for ever and permanently block re-delivery.
 * [io.whozoss.agentos.caseFlow.Neo4jCaseRepository.delete] rewrites the key to a tombstone form on
 * soft-delete, the same treatment `ScheduledPromptNode.tripleKey` and `SkillNode.doubleKey` receive.
 */
object DispatchKey {
    /** Separator between components. Outside the UUID alphabet, so components never blur together. */
    const val SEPARATOR: String = ":"

    /**
     * Prefix freeing the unique slot held by a soft-deleted case. Each tombstone embeds the case
     * id, itself unique, so tombstones never collide with each other either.
     */
    const val TOMBSTONE_PREFIX: String = "tombstone:"

    /**
     * `runId:userId` — one delivery per user per run.
     *
     * Use when the step feeding the fan-out produced users, so there is no entity to distinguish
     * two deliveries to the same person.
     */
    fun of(
        runId: UUID,
        userId: UUID,
    ): String = "$runId$SEPARATOR$userId"

    /**
     * `runId:userId:entityId` — one delivery per entity per user per run.
     *
     * Use when the fan-out iterates entities: the same user reached by several entities receives
     * one case for each, and each is independently idempotent.
     */
    fun of(
        runId: UUID,
        userId: UUID,
        entityId: String,
    ): String = "$runId$SEPARATOR$userId$SEPARATOR$entityId"

    /** The key a soft-deleted case keeps, so its live key is released for re-delivery. */
    fun tombstone(caseId: UUID): String = "$TOMBSTONE_PREFIX$caseId"
}

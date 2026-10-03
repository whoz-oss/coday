package io.whozoss.factory.adapter.agentos

import java.util.concurrent.ConcurrentHashMap

/**
 * Restart-safe checkpoint shape `(timestamp, lastEventId)`, mirroring the
 * AgentOS-side `FactorySseHighWaterMarkStore` (see
 * `app_docs/3d9fc3b1_durable-factory-bridge.md`). Because AgentOS durable
 * ordering is `timestamp ASC, id ASC`, this mark is a valid incremental
 * checkpoint even though the SSE endpoint honours no cursor.
 */
data class HighWaterMark(
    val timestamp: String?,
    val lastEventId: String?,
) {
    /** True when the mark points at a concrete event; an empty mark covers nothing. */
    fun isPositioned(): Boolean = timestamp != null && lastEventId != null
}

/** Identity of an observed execution attempt; checkpoints are keyed by it. */
data class CheckpointKey(val caseId: String, val attemptId: String)

/**
 * Per-`(caseId, attemptId)` event checkpoint: monotone `(timestamp, id)`
 * high-water mark plus a bounded rolling window of already-seen event ids.
 *
 * Deduplication semantics (per the frozen SSE contract):
 * - The server replays the **full** durable history on every connection, so a
 *   duplicate is any event whose `eventId` was already recorded, or that is
 *   covered by the high-water mark (fallback once an id has been evicted from
 *   the bounded window).
 * - A fresh/empty checkpoint means "full replay + eventId dedup" — never a
 *   verdict by silence.
 */
class EventCheckpoint(
    private val windowSize: Int = DEFAULT_SEEN_WINDOW,
    baseline: HighWaterMark = HighWaterMark(null, null),
) {

    private val lock = Any()
    private val seen = LinkedHashMap<String, CaseEventView>()

    /**
     * The dedup high-water mark, pre-positioned on the per-turn [baseline]
     * when one was captured: every pre-turn event of a reused case is then
     * `covered` and ignored at replay — it can never close the new turn.
     */
    @Volatile
    var mark: HighWaterMark = baseline
        private set

    /**
     * Seed the per-turn baseline on a fresh checkpoint (no-op once the mark
     * is positioned: the mark only moves forward). Lets an observer that
     * started before [HighWaterMarkStore.setBaseline] still fence out the
     * pre-turn events.
     */
    fun seedBaseline(baseline: HighWaterMark) {
        if (!baseline.isPositioned()) return
        synchronized(lock) {
            if (!mark.isPositioned()) mark = baseline
        }
    }

    /**
     * Record [event]. Returns true when this is the first sight of the event
     * (caller must process it), false when it is a duplicate (replay/live
     * double-delivery) and must be skipped.
     */
    fun record(event: CaseEventView): Boolean = synchronized(lock) {
        if (isDuplicateLocked(event)) return false
        seen[event.eventId] = event
        if (seen.size > windowSize) {
            val iterator = seen.entries.iterator()
            iterator.next()
            iterator.remove()
        }
        mark = advance(mark, event)
        true
    }

    fun isDuplicate(event: CaseEventView): Boolean = synchronized(lock) { isDuplicateLocked(event) }

    /**
     * The recorded events still held in the window, in recording order. Lets
     * an observer seed its in-memory history from a warm checkpoint so verdict
     * derivation always runs over the full known history, not just a delta.
     */
    fun events(): List<CaseEventView> = synchronized(lock) { seen.values.toList() }

    private fun isDuplicateLocked(event: CaseEventView): Boolean =
        event.eventId in seen || covers(mark, event)

    companion object {
        /**
         * Rolling window of acknowledged event ids kept per checkpoint. A
         * case history is unbounded and durable forever, so the seen-set is
         * capped; ids evicted from the window remain covered by the
         * `(timestamp, id)` high-water mark.
         */
        const val DEFAULT_SEEN_WINDOW = 10_000

        /** True when [event] sorts at or before [mark] in `(timestamp, id)` order. */
        fun covers(mark: HighWaterMark, event: CaseEventView): Boolean {
            val markTs = mark.timestamp ?: return false
            val markId = mark.lastEventId ?: return false
            val eventTs = event.timestamp ?: return false
            return eventTs < markTs || (eventTs == markTs && event.eventId <= markId)
        }

        /** Monotone advance: the mark only moves forward in `(timestamp, id)` order. */
        fun advance(mark: HighWaterMark, event: CaseEventView): HighWaterMark {
            val eventTs = event.timestamp ?: return mark
            val markTs = mark.timestamp
            val markId = mark.lastEventId
            return if (markTs == null || eventTs > markTs || (eventTs == markTs && (markId == null || event.eventId > markId))) {
                HighWaterMark(eventTs, event.eventId)
            } else {
                mark
            }
        }

        /**
         * Capture the per-turn baseline: the high-water mark covering the
         * whole durable, non-transient history present **before** a new turn
         * starts. Events without a timestamp cannot be ordered and never
         * move the mark.
         */
        fun baselineOf(events: List<CaseEventView>): HighWaterMark =
            events.filter { !it.isTransient() }
                .fold(HighWaterMark(null, null)) { mark, event -> advance(mark, event) }
    }
}

/**
 * Process-local store of [EventCheckpoint]s keyed by `(caseId, attemptId)`.
 *
 * Phase-1 volatility warning: this store is an in-memory `ConcurrentHashMap`.
 * Losing a checkpoint (process restart) is **safe by construction**: the next
 * observation is treated as a fresh start (full replay + eventId dedup) and
 * never as a verdict by silence. Durable Factory-side persistence is an
 * explicit follow-up; the AgentOS-side durable store already exists.
 */
class HighWaterMarkStore {
    private val checkpoints = ConcurrentHashMap<CheckpointKey, EventCheckpoint>()

    /** (caseId, attemptId) → per-turn baseline captured before that turn started. */
    private val baselines = ConcurrentHashMap<CheckpointKey, HighWaterMark>()

    /** caseId → most recently captured baseline of any of its turns. */
    private val latestBaselineByCase = ConcurrentHashMap<String, HighWaterMark>()

    fun checkpoint(caseId: String, attemptId: String): EventCheckpoint =
        checkpoints.getOrPut(CheckpointKey(caseId, attemptId)) { EventCheckpoint() }

    /**
     * The checkpoint of a turn, seeded with the turn [baseline] so the full
     * SSE replay of a reused case skips every pre-turn event instead of
     * deriving a premature verdict from it.
     */
    fun checkpointWithBaseline(caseId: String, attemptId: String, baseline: HighWaterMark): EventCheckpoint {
        val checkpoint = checkpoints.getOrPut(CheckpointKey(caseId, attemptId)) { EventCheckpoint(baseline = baseline) }
        checkpoint.seedBaseline(baseline)
        return checkpoint
    }

    /** Record the baseline captured before the `(caseId, attemptId)` turn started. */
    fun setBaseline(caseId: String, attemptId: String, baseline: HighWaterMark) {
        baselines[CheckpointKey(caseId, attemptId)] = baseline
        latestBaselineByCase[caseId] = baseline
    }

    /** The baseline of the `(caseId, attemptId)` turn, when one was captured. */
    fun baseline(caseId: String, attemptId: String): HighWaterMark? = baselines[CheckpointKey(caseId, attemptId)]

    /** The most recently captured baseline of any turn of [caseId], when known. */
    fun latestBaseline(caseId: String): HighWaterMark? = latestBaselineByCase[caseId]

    fun clear(caseId: String, attemptId: String) {
        checkpoints.remove(CheckpointKey(caseId, attemptId))
        baselines.remove(CheckpointKey(caseId, attemptId))
    }
}

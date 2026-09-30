package io.whozoss.agentos.plugins.factorybridge.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import mu.KLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bridge-side SSE high-water mark for a single `(case, attempt)` observation stream.
 *
 * @property timestamp timestamp of the last fully processed event.
 * @property lastEventId stable id of the last fully processed event at [timestamp]. Used
 *   to break ties when several events share the same timestamp (the AgentOS SSE contract
 *   orders by `(timestamp, id)`).
 */
data class SseHighWaterMark(
    val timestamp: Instant,
    val lastEventId: String,
) {
    /**
     * `true` when the candidate `(timestamp, eventId)` has already been processed and must
     * be skipped by a replay-based observation protocol.
     */
    fun covers(
        timestamp: Instant,
        eventId: String,
    ): Boolean =
        when {
            timestamp.isBefore(this.timestamp) -> true
            timestamp.isAfter(this.timestamp) -> false
            else -> eventId <= lastEventId
        }

    /** Returns the greater of this mark and the candidate, per the `(timestamp, id)` order. */
    fun max(
        timestamp: Instant,
        eventId: String,
    ): SseHighWaterMark =
        if (covers(timestamp, eventId)) this else SseHighWaterMark(timestamp, eventId)
}

/**
 * Restart-safe, file-backed store for bridge-side SSE high-water marks.
 *
 * The AgentOS observation protocol replays the full event stream and deduplicates by
 * `eventId`; the high-water mark is the `(timestamp, id)` cursor of the last fully
 * processed event. Keeping it only in memory would force a full re-processing of every
 * already-handled event after a restart, so this store persists one mark per
 * `(caseId, attemptId)` pair using the same atomic write-temp-then-rename strategy as
 * [FactoryBridgeStateStore].
 *
 * A missing file starts empty and a corrupt file is logged and starts empty: the
 * high-water mark is an optimisation/deduplication cursor, so losing it is safe (events
 * are simply re-observed and re-deduplicated downstream), never a correctness hazard.
 *
 * @param file destination file, or `null` for an in-memory store.
 * @param objectMapper the plugin's Jackson mapper.
 */
class FactorySseHighWaterMarkStore(
    private val file: Path?,
    private val objectMapper: ObjectMapper,
) {
    companion object : KLogging() {
        fun open(
            dataDir: String?,
            objectMapper: ObjectMapper,
        ): FactorySseHighWaterMarkStore =
            FactorySseHighWaterMarkStore(
                file = dataDir
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it).resolve(FactoryBridgeStateStore.HIGH_WATER_MARK_FILE) },
                objectMapper = objectMapper,
            )
    }

    private val lock = ReentrantLock()
    private val marks: MutableMap<String, SseHighWaterMark> = LinkedHashMap()

    init {
        load()
    }

    fun get(
        caseId: UUID,
        attemptId: String,
    ): SseHighWaterMark? = lock.withLock { marks[key(caseId, attemptId)] }

    /**
     * Advances the stored mark to at least `(timestamp, eventId)` and returns the
     * resulting mark. The mark never moves backwards.
     */
    fun advance(
        caseId: UUID,
        attemptId: String,
        timestamp: Instant,
        eventId: String,
    ): SseHighWaterMark {
        val composite = key(caseId, attemptId)
        return lock.withLock {
            val current = marks[composite]
            val next = current?.max(timestamp, eventId) ?: SseHighWaterMark(timestamp, eventId)
            if (current != next) {
                marks[composite] = next
                persist()
            }
            next
        }
    }

    /**
     * `true` when `(timestamp, eventId)` has already been processed for this
     * `(caseId, attemptId)` and must therefore be skipped during a replay.
     */
    fun isDuplicate(
        caseId: UUID,
        attemptId: String,
        timestamp: Instant,
        eventId: String,
    ): Boolean = get(caseId, attemptId)?.covers(timestamp, eventId) ?: false

    fun clear(
        caseId: UUID,
        attemptId: String,
    ) {
        lock.withLock {
            if (marks.remove(key(caseId, attemptId)) != null) persist()
        }
    }

    private fun key(
        caseId: UUID,
        attemptId: String,
    ) = "$caseId|$attemptId"

    private fun load() {
        val target = file ?: return
        if (!Files.exists(target)) return
        runCatching {
            val persisted = objectMapper.readValue(Files.readString(target), PersistedHighWaterMarks::class.java)
            lock.withLock {
                marks.clear()
                persisted.marks.forEach { mark ->
                    runCatching {
                        marks["${UUID.fromString(mark.caseId)}|${mark.attemptId}"] =
                            SseHighWaterMark(Instant.ofEpochMilli(mark.timestampEpochMilli), mark.lastEventId)
                    }.onFailure {
                        logger.warn { "Skipping malformed persisted SSE high-water mark for case '${mark.caseId}'" }
                    }
                }
            }
        }.onFailure { error ->
            logger.warn(error) { "SSE high-water marks unreadable at $target — starting empty" }
            lock.withLock { marks.clear() }
        }
    }

    private fun persist() {
        val target = file ?: return
        val snapshot =
            PersistedHighWaterMarks(
                marks =
                    marks.map { (composite, mark) ->
                        val separator = composite.indexOf('|')
                        PersistedHighWaterMark(
                            caseId = composite.substring(0, separator),
                            attemptId = composite.substring(separator + 1),
                            timestampEpochMilli = mark.timestamp.toEpochMilli(),
                            lastEventId = mark.lastEventId,
                        )
                    },
            )
        runCatching {
            target.parent?.let { Files.createDirectories(it) }
            val payload = objectMapper.writeValueAsString(snapshot)
            val temp = target.resolveSibling("${target.fileName}.tmp")
            Files.writeString(temp, payload)
            runCatching {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { error ->
            logger.error(error) { "SSE high-water marks could not be persisted to $target" }
        }
    }
}

internal data class PersistedHighWaterMarks(
    val marks: List<PersistedHighWaterMark> = emptyList(),
)

internal data class PersistedHighWaterMark(
    val caseId: String,
    val attemptId: String,
    val timestampEpochMilli: Long,
    val lastEventId: String,
)

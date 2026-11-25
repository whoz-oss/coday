package io.whozoss.factory.settings

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Service
import java.time.Instant

/** The effective run-concurrency setting plus the metadata the Admin page displays. */
data class RunConcurrencySettings(
    val maxParallelRuns: Int,
    val defaultMaxParallelRuns: Int,
    val min: Int,
    val max: Int,
    val updatedAt: Instant?,
    val updatedBy: String?,
)

/**
 * Maximum number of workflow continuations the outbox drain worker runs in parallel.
 *
 * The value is global to the Factory instance (not per organization): the drain pool is
 * process-wide and serves every organization. An administrator changes it at runtime; it is
 * persisted in a single `:FactorySetting` node so that it survives a restart. Until a value is
 * persisted, the property `factory.outbox.max-parallel-runs` (default 5) applies.
 *
 * The hot path, [maxParallelRuns], reads memory only. Neo4j is read once (lazily, fail-soft:
 * on a database error the default applies and the read is retried at the next call) and is
 * written by [update], whose in-memory value changes only after the write succeeded.
 * The value is always within [MIN]..[MAX].
 */
@Service
class RunConcurrencySettingsService(
    private val neo4jClient: Neo4jClient,
    @Value("\${factory.outbox.max-parallel-runs:5}") configuredDefault: Int,
) : RunConcurrencyLimit {

    private val logger = KotlinLogging.logger {}

    /** The boot default, clamped into the allowed range. */
    val defaultMaxParallelRuns: Int = configuredDefault.coerceIn(MIN, MAX).also {
        if (it != configuredDefault) {
            logger.warn { "factory.outbox.max-parallel-runs=$configuredDefault is outside $MIN..$MAX; using $it" }
        }
    }

    /** Null until the persisted value has been read successfully. */
    @Volatile
    private var current: RunConcurrencySettings? = null

    /** Hot path: the current limit, always within [MIN]..[MAX]. */
    fun maxParallelRuns(): Int = settings().maxParallelRuns

    override fun current(): Int = maxParallelRuns()

    fun settings(): RunConcurrencySettings = current ?: load()

    /**
     * Validates and persists a new limit, then applies it to the running worker.
     *
     * @throws InvalidRunConcurrencyException when [value] is outside [MIN]..[MAX]; the previous
     *   value is kept.
     */
    @Synchronized
    fun update(value: Int, actorId: String): RunConcurrencySettings {
        if (value !in MIN..MAX) throw InvalidRunConcurrencyException()
        val previous = settings().maxParallelRuns
        val now = Instant.now()
        neo4jClient.query(
            "MERGE (s:FactorySetting {id: \$id}) " +
                "SET s.intValue = \$value, s.updatedAt = \$updatedAt, s.updatedBy = \$updatedBy " +
                "RETURN s.id AS id",
        ).bindAll(
            mapOf("id" to SETTING_ID, "value" to value, "updatedAt" to now.toString(), "updatedBy" to actorId),
        ).fetch().one().orElseThrow()
        val updated = snapshot(value, now, actorId)
        current = updated
        logger.info { "Max parallel workflow runs changed $previous -> $value by $actorId" }
        return updated
    }

    /**
     * Reads the persisted value once. On a database error the default applies but nothing is
     * cached, so the next call retries.
     */
    @Synchronized
    private fun load(): RunConcurrencySettings {
        val cached = current
        return when {
            cached != null -> cached
            else -> {
                val read = runCatching { readPersisted() }
                    .onFailure { logger.warn(it) { "Could not read the run concurrency setting; using the default" } }
                val value = read.getOrNull() ?: snapshot(defaultMaxParallelRuns, null, null)
                if (read.isSuccess) current = value
                value
            }
        }
    }

    private fun readPersisted(): RunConcurrencySettings? {
        val row = neo4jClient.query(
            "MATCH (s:FactorySetting {id: \$id}) " +
                "RETURN s.intValue AS value, s.updatedAt AS updatedAt, s.updatedBy AS updatedBy",
        ).bind(SETTING_ID).to("id").fetch().one().orElse(null) ?: return null
        val persisted = (row["value"] as? Number)?.toInt() ?: return null
        return snapshot(
            persisted.coerceIn(MIN, MAX),
            (row["updatedAt"] as? String)?.let { text -> runCatching { Instant.parse(text) }.getOrNull() },
            row["updatedBy"] as? String,
        )
    }

    private fun snapshot(value: Int, updatedAt: Instant?, updatedBy: String?) =
        RunConcurrencySettings(value, defaultMaxParallelRuns, MIN, MAX, updatedAt, updatedBy)

    companion object {
        /** Lower bound: 1 restores the historical serialized behaviour. */
        const val MIN = 1

        /** Hard ceiling so that a typo can never create an unbounded thread pool. */
        const val MAX = 20

        const val SETTING_ID = "outbox.max-parallel-runs"
    }
}

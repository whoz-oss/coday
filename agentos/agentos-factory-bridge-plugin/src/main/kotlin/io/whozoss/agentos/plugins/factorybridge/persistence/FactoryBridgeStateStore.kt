package io.whozoss.agentos.plugins.factorybridge.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.plugins.factorybridge.FactoryCheckpointRef
import mu.KLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Durable snapshot of a Factory step-result binding.
 *
 * Mirrors [io.whozoss.agentos.plugins.factorybridge.FactoryStepResultBinding] without the
 * mutable [java.util.concurrent.atomic.AtomicBoolean] lease flag: the state store only
 * needs a plain boolean because every mutation is serialized under the store lock and
 * flushed atomically to disk.
 */
data class FactoryStepResultBindingState(
    val caseId: UUID,
    val namespaceId: UUID,
    val agentName: String,
    val attemptId: String,
    val runtimeId: String,
    val capabilityToken: String,
    val expiresAt: Instant,
    val leased: Boolean,
)

/**
 * File-backed, restart-safe state store for the Factory Bridge plugin.
 *
 * Historically the plugin kept every binding, lease and pending human checkpoint in a
 * process-local [java.util.concurrent.ConcurrentHashMap], so an AgentOS restart silently
 * dropped them — losing in-flight step-result capabilities and pending approvals. This
 * store mirrors that state to a single JSON file using an atomic
 * *write-temp-then-rename* sequence, so a crash mid-write can never leave a truncated
 * file behind.
 *
 * ### Fail-closed semantics
 *
 * A missing file (first run) starts empty. A corrupt or unreadable file is logged and
 * also starts empty: losing bindings is **fail-closed** — no capability means a step
 * result can never be submitted — whereas silently resurrecting a partial state could
 * reopen a stale capability. All state-transition semantics (single-flight CAS lease,
 * expiry, invalidation) remain owned by
 * [io.whozoss.agentos.plugins.factorybridge.FactoryStepResultBindingRegistry]; this class
 * only persists what it is told.
 *
 * ### Thread-safety
 *
 * All reads and mutations are serialized by a [ReentrantLock]; the persisted file is
 * rewritten atomically on every mutation so durability does not depend on an explicit
 * flush/close.
 *
 * @param file destination file, or `null` for a purely in-memory store (unit tests and
 *   any deployment without `AGENTOS_FACTORY_BRIDGE_DATA_DIR`).
 * @param objectMapper the plugin's Jackson mapper, reused so serialization stays
 *   consistent with the rest of the bridge.
 */
class FactoryBridgeStateStore(
    private val file: Path?,
    private val objectMapper: ObjectMapper,
) {
    companion object : KLogging() {
        const val DEFAULT_DATA_DIR = "data/factory-bridge"
        const val STATE_FILE = "bridge-state.json"
        const val HIGH_WATER_MARK_FILE = "sse-high-water-marks.json"

        /**
         * Opens a store rooted at [dataDir]. A blank/null directory yields an in-memory
         * store (nothing is written to disk).
         */
        fun open(
            dataDir: String?,
            objectMapper: ObjectMapper,
            fileName: String = STATE_FILE,
        ): FactoryBridgeStateStore =
            FactoryBridgeStateStore(
                file = dataDir?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(fileName) },
                objectMapper = objectMapper,
            )
    }

    private val lock = ReentrantLock()
    private val bindings: MutableMap<UUID, FactoryStepResultBindingState> = LinkedHashMap()
    private val checkpoints: MutableMap<UUID, FactoryCheckpointRef> = LinkedHashMap()
    private val stepQuestions: MutableMap<UUID, FactoryCheckpointRef> = LinkedHashMap()

    /** Live [MutableMap] view used by [FactoryBridgeServices.pendingCheckpoints]. */
    val checkpointMap: MutableMap<UUID, FactoryCheckpointRef> =
        DurableCheckpointMap(lock, checkpoints) { persist() }

    /** Restart-safe question-event to Factory interaction correlation. */
    val stepQuestionMap: MutableMap<UUID, FactoryCheckpointRef> =
        DurableCheckpointMap(lock, stepQuestions) { persist() }

    init {
        load()
    }

    // ------------------------------------------------------------------
    // Bindings
    // ------------------------------------------------------------------

    fun binding(caseId: UUID): FactoryStepResultBindingState? = lock.withLock { bindings[caseId] }

    fun bindings(): List<FactoryStepResultBindingState> = lock.withLock { bindings.values.toList() }

    fun putBinding(state: FactoryStepResultBindingState) {
        lock.withLock {
            bindings[state.caseId] = state
            persist()
        }
    }

    fun removeBinding(caseId: UUID) {
        lock.withLock {
            if (bindings.remove(caseId) != null) persist()
        }
    }

    fun setLease(
        caseId: UUID,
        leased: Boolean,
    ) {
        lock.withLock {
            val existing = bindings[caseId] ?: return@withLock
            if (existing.leased == leased) return@withLock
            bindings[caseId] = existing.copy(leased = leased)
            persist()
        }
    }

    // ------------------------------------------------------------------
    // Pending human checkpoints
    // ------------------------------------------------------------------

    fun checkpoint(caseId: UUID): FactoryCheckpointRef? = lock.withLock { checkpoints[caseId] }

    fun checkpoints(): Map<UUID, FactoryCheckpointRef> = lock.withLock { LinkedHashMap(checkpoints) }

    fun putCheckpoint(
        caseId: UUID,
        reference: FactoryCheckpointRef,
    ) {
        lock.withLock {
            checkpoints[caseId] = reference
            persist()
        }
    }

    fun removeCheckpoint(caseId: UUID) {
        lock.withLock {
            if (checkpoints.remove(caseId) != null) persist()
        }
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    private fun load() {
        val target = file ?: return
        if (!Files.exists(target)) return
        runCatching {
            val persisted = objectMapper.readValue(Files.readString(target), PersistedBridgeState::class.java)
            lock.withLock {
                bindings.clear()
                checkpoints.clear()
                stepQuestions.clear()
                persisted.bindings.forEach { binding ->
                    toState(binding)?.let { bindings[it.caseId] = it }
                }
                persisted.checkpoints.forEach { checkpoint ->
                    toCheckpoint(checkpoint)?.let { checkpoints[it.first] = it.second }
                }
                persisted.stepQuestions.forEach { checkpoint ->
                    toCheckpoint(checkpoint)?.let { stepQuestions[it.first] = it.second }
                }
            }
        }.onFailure { error ->
            logger.warn(error) { "Factory bridge state unreadable at $target — starting empty (fail-closed)" }
            lock.withLock {
                bindings.clear()
                checkpoints.clear()
                stepQuestions.clear()
            }
        }
    }

    private fun persist() {
        val target = file ?: return
        val snapshot =
            PersistedBridgeState(
                bindings = bindings.values.map { toPersisted(it) },
                checkpoints = checkpoints.map { (caseId, ref) -> toPersisted(caseId, ref) },
                stepQuestions = stepQuestions.map { (questionId, ref) -> toPersisted(questionId, ref) },
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
            logger.error(error) { "Factory bridge state could not be persisted to $target" }
        }
    }

    private fun toPersisted(state: FactoryStepResultBindingState) =
        PersistedBinding(
            caseId = state.caseId.toString(),
            namespaceId = state.namespaceId.toString(),
            agentName = state.agentName,
            attemptId = state.attemptId,
            runtimeId = state.runtimeId,
            capabilityToken = state.capabilityToken,
            expiresAtEpochMilli = state.expiresAt.toEpochMilli(),
            leased = state.leased,
        )

    private fun toState(binding: PersistedBinding): FactoryStepResultBindingState? =
        runCatching {
            FactoryStepResultBindingState(
                caseId = UUID.fromString(binding.caseId),
                namespaceId = UUID.fromString(binding.namespaceId),
                agentName = binding.agentName,
                attemptId = binding.attemptId,
                runtimeId = binding.runtimeId,
                capabilityToken = binding.capabilityToken,
                expiresAt = Instant.ofEpochMilli(binding.expiresAtEpochMilli),
                leased = binding.leased,
            )
        }.getOrElse {
            logger.warn { "Skipping malformed persisted Factory binding '${binding.caseId}'" }
            null
        }

    private fun toPersisted(
        caseId: UUID,
        ref: FactoryCheckpointRef,
    ) = PersistedCheckpoint(
        caseId = caseId.toString(),
        workflowId = ref.workflowId,
        interactionId = ref.interactionId,
        interactionRevision = ref.interactionRevision,
    )

    private fun toCheckpoint(checkpoint: PersistedCheckpoint): Pair<UUID, FactoryCheckpointRef>? =
        runCatching {
            UUID.fromString(checkpoint.caseId) to
                FactoryCheckpointRef(
                    workflowId = checkpoint.workflowId,
                    interactionId = checkpoint.interactionId,
                    interactionRevision = checkpoint.interactionRevision,
                )
        }.getOrElse {
            logger.warn { "Skipping malformed persisted Factory checkpoint '${checkpoint.caseId}'" }
            null
        }
}

/**
 * [MutableMap] decorator that mirrors every mutation into the parent
 * [FactoryBridgeStateStore] so pending checkpoints survive an AgentOS restart.
 *
 * Delegation keeps the full [MutableMap] contract (including `get`/`containsKey` used by
 * the answer interceptor) while only intercepting the mutating operations that need to be
 * persisted.
 */
internal class DurableCheckpointMap(
    private val lock: ReentrantLock,
    private val delegate: MutableMap<UUID, FactoryCheckpointRef>,
    private val onMutate: () -> Unit,
) : MutableMap<UUID, FactoryCheckpointRef> by delegate {
    override val size: Int get() = lock.withLock { delegate.size }

    override fun isEmpty(): Boolean = lock.withLock { delegate.isEmpty() }

    override fun containsKey(key: UUID): Boolean = lock.withLock { delegate.containsKey(key) }

    override fun containsValue(value: FactoryCheckpointRef): Boolean = lock.withLock { delegate.containsValue(value) }

    override fun get(key: UUID): FactoryCheckpointRef? = lock.withLock { delegate[key] }

    override fun put(
        key: UUID,
        value: FactoryCheckpointRef,
    ): FactoryCheckpointRef? = lock.withLock { delegate.put(key, value) }.also { onMutate() }

    override fun remove(key: UUID): FactoryCheckpointRef? = lock.withLock { delegate.remove(key) }.also { onMutate() }

    override fun putAll(from: Map<out UUID, FactoryCheckpointRef>) {
        lock.withLock { delegate.putAll(from) }
        onMutate()
    }

    override fun clear() {
        val mutated = lock.withLock {
            if (delegate.isEmpty()) {
                false
            } else {
                delegate.clear()
                true
            }
        }
        if (mutated) onMutate()
    }
}

internal data class PersistedBridgeState(
    val bindings: List<PersistedBinding> = emptyList(),
    val checkpoints: List<PersistedCheckpoint> = emptyList(),
    val stepQuestions: List<PersistedCheckpoint> = emptyList(),
)

internal data class PersistedBinding(
    val caseId: String,
    val namespaceId: String,
    val agentName: String,
    val attemptId: String,
    val runtimeId: String,
    val capabilityToken: String,
    val expiresAtEpochMilli: Long,
    val leased: Boolean,
)

internal data class PersistedCheckpoint(
    val caseId: String,
    val workflowId: String,
    val interactionId: String,
    val interactionRevision: Long,
)

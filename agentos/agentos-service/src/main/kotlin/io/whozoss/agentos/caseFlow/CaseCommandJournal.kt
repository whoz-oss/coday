package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.ExchangeRootResolver
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Fully expanded input, including the submitting user and context, frozen before acknowledgement. */
data class DurableCaseCommand(
    val id: UUID = UUID.randomUUID(),
    val actor: Actor,
    val content: List<MessageContent>,
    val sessionContext: Map<String, Any?>? = null,
)

data class CaseCommandBatch(val commands: List<DurableCaseCommand>)

@Node("CaseCommandReceipt")
data class CaseCommandReceipt(
    @Id val id: String,
    val caseId: String,
    val originalJson: String,
    val batchJson: String,
    val created: Instant = Instant.now(),
    val cursor: Int = 0,
    val state: String = "QUEUED",
)

interface CaseCommandReceiptRepository : Neo4jRepository<CaseCommandReceipt, String> {
    @Query($$"MATCH (r:CaseCommandReceipt {caseId: $caseId}) RETURN r ORDER BY r.created, r.id")
    fun forCase(caseId: String): List<CaseCommandReceipt>

    @Query($$"MATCH (r:CaseCommandReceipt {caseId: $caseId}) WHERE r.state IN $states RETURN count(r) > 0")
    fun hasState(caseId: String, states: Collection<String>): Boolean

    @Query($$"MATCH (r:CaseCommandReceipt {caseId: $caseId}) WHERE r.state IN $states RETURN r ORDER BY r.created, r.id LIMIT 1")
    fun firstInState(caseId: String, states: Collection<String>): CaseCommandReceipt?

    @Query($$"MATCH (r:CaseCommandReceipt {caseId: $caseId}) WHERE r.state IN $states SET r.state = $target")
    fun transition(caseId: String, states: Collection<String>, target: String)

    @Query("MATCH (r:CaseCommandReceipt) WHERE r.state = 'STARTED' SET r.state = 'RECOVERY_REQUIRED'")
    fun recoverInterrupted()

    @Query("MATCH (r:CaseCommandReceipt) WHERE r.state IN ['QUEUED', 'WAITING'] RETURN DISTINCT r.caseId")
    fun pendingCases(): List<String>

    @Query("MATCH (r:CaseCommandReceipt) RETURN DISTINCT r.caseId")
    fun recordedCases(): List<String>
}

/** Single-instance inbox; unrelated cases do not share an execution lock. */
@Service
class CaseCommandJournal(
    private val repository: CaseCommandReceiptRepository,
    private val mapper: ObjectMapper,
    private val roots: ExchangeRootResolver,
) {
    private val locks = java.util.concurrent.ConcurrentHashMap<UUID, Any>()
    fun equipped(caseId: UUID): Boolean = roots.resolve(caseId).binding != null
    fun rootId(caseId: UUID): UUID? = roots.resolve(caseId).binding?.rootCaseId
    fun accepting(caseId: UUID) {
        val b = roots.resolve(caseId).binding ?: return
        if (b.status in setOf(io.whozoss.agentos.git.CaseResourceStatus.DELETING, io.whozoss.agentos.git.CaseResourceStatus.REMOVED)) {
            throw ConflictException("The workspace is being removed or has been removed")
        }
    }
    fun <T> locked(caseId: UUID, block: () -> T): T = synchronized(locks.computeIfAbsent(caseId) { Any() }) { block() }
    private fun key(caseId: UUID, requestId: UUID) = "$caseId:$requestId"
    fun duplicate(caseId: UUID, requestId: UUID, original: Any): Boolean {
        val previous = repository.findById(key(caseId, requestId)).orElse(null) ?: return false
        if (mapper.readTree(previous.originalJson) != mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(original)) {
            throw ConflictException("This message request id was already used for different content")
        }
        return true
    }
    fun append(
        caseId: UUID,
        requestId: UUID,
        original: Any,
        commands: List<DurableCaseCommand>,
    ): MessageEvent = io.whozoss.agentos.git.WorkspaceLifecycleLocks.withRoot(rootId(caseId) ?: caseId) {
        locked(caseId) {
            accepting(caseId)
            require(commands.isNotEmpty()) { "An instruction must contain at least one command" }
            val receipt = if (duplicate(caseId, requestId, original)) {
                repository.findById(key(caseId, requestId)).orElseThrow()
            } else {
                repository.save(CaseCommandReceipt(
                    key(caseId, requestId), caseId.toString(), mapper.writeValueAsString(original),
                    mapper.writeValueAsString(CaseCommandBatch(commands)),
                ))
            }
            receivedMessage(receipt, requireNotNull(roots.resolve(caseId).binding).namespaceId)
        }
    }

    /**
     * Conversation-only projection of accepted input, including input cancelled before execution.
     * The first command has the same identity as its later MessageEvent; presentation therefore
     * shows it once. Agent history must keep reading the event store, never this projection.
     */
    fun receivedMessages(caseId: UUID, materializedIds: Set<UUID>): List<MessageEvent> {
        val receipts = repository.forCase(caseId.toString())
        if (receipts.isEmpty()) return emptyList()
        val namespaceId = roots.resolve(caseId).binding?.namespaceId ?: return emptyList()
        return receipts.map { receivedMessage(it, namespaceId) }.filter { it.id !in materializedIds }
    }

    private fun receivedMessage(receipt: CaseCommandReceipt, namespaceId: UUID): MessageEvent {
        val command = mapper.readValue(receipt.batchJson, CaseCommandBatch::class.java).commands.first()
        return MessageEvent(
            metadata = EntityMetadata(id = command.id, created = receipt.created, modified = receipt.created),
            namespaceId = namespaceId,
            caseId = UUID.fromString(receipt.caseId),
            timestamp = receipt.created,
            actor = command.actor,
            content = command.content,
            sessionContext = command.sessionContext,
        )
    }
    fun hasUnfinished(caseId: UUID) = repository.hasState(caseId.toString(), UNFINISHED_STATES)
    fun recoveryRequired(caseId: UUID) = repository.hasState(caseId.toString(), listOf("RECOVERY_REQUIRED"))
    fun hasPending(caseId: UUID) = repository.hasState(caseId.toString(), listOf("QUEUED", "WAITING"))
    fun isWaiting(caseId: UUID) = repository.hasState(caseId.toString(), listOf("WAITING"))
    fun hasReceipt(caseId: UUID, requestId: UUID) = repository.existsById(key(caseId, requestId))
    fun recordedCases() = repository.recordedCases().map(UUID::fromString)
    fun pendingCases() = repository.pendingCases().map(UUID::fromString)

    fun next(caseId: UUID): DurableCaseCommand? = locked(caseId) {
        if (repository.hasState(caseId.toString(), listOf("STARTED", "WAITING", "RECOVERY_REQUIRED"))) return@locked null
        val row = repository.firstInState(caseId.toString(), listOf("QUEUED")) ?: return@locked null
        val command = mapper.readValue(row.batchJson, CaseCommandBatch::class.java).commands[row.cursor]
        repository.save(row.copy(state = "STARTED")) // before any event or agent side effect
        command
    }
    fun complete(caseId: UUID, commandId: UUID) = locked(caseId) {
        repository.firstInState(caseId.toString(), listOf("STARTED"))?.let { row ->
            val commands = mapper.readValue(row.batchJson, CaseCommandBatch::class.java).commands
            check(commands[row.cursor].id == commandId)
            repository.save(row.copy(cursor = row.cursor + 1, state = if (row.cursor + 1 == commands.size) "DONE" else "QUEUED"))
        }
    }
    fun waitForAnswer(caseId: UUID) = locked(caseId) {
        repository.firstInState(caseId.toString(), listOf("STARTED"))?.let { repository.save(it.copy(state = "WAITING")) }
    }
    fun resumeAnswer(caseId: UUID): DurableCaseCommand? = locked(caseId) {
        val row = repository.firstInState(caseId.toString(), listOf("WAITING")) ?: return@locked null
        repository.save(row.copy(state = "STARTED"))
        mapper.readValue(row.batchJson, CaseCommandBatch::class.java).commands[row.cursor]
    }
    fun cancel(caseId: UUID) = locked(caseId) {
        repository.transition(caseId.toString(), UNFINISHED_STATES, "CANCELLED")
    }
    fun fail(caseId: UUID) = locked(caseId) {
        repository.transition(caseId.toString(), listOf("STARTED"), "RECOVERY_REQUIRED")
    }
    /** Explicitly abandon uncertain work; subsequent input starts a fresh turn on the same worktree. */
    fun acknowledge(caseId: UUID) = cancel(caseId)

    @EventListener(ApplicationReadyEvent::class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    fun recoverInterrupted() = repository.recoverInterrupted()

    companion object {
        private val UNFINISHED_STATES = listOf("QUEUED", "STARTED", "WAITING", "RECOVERY_REQUIRED")
    }
}

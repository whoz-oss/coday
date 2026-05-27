package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.TransientCaseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class CaseServiceImpl(
    private val agentService: AgentService,
    private val agentConfigService: AgentConfigService,
    private val agentConfigProperties: AgentConfigProperties,
    private val caseRepository: CaseRepository,
    private val caseEventService: CaseEventService,
    private val userService: UserService,
    private val namespaceService: NamespaceService,
) : CaseService {
    /**
     * Coroutine scope used to run case execution loops in the background.
     * Each [run] call is launched here so HTTP threads are never blocked.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeRuntimes = ConcurrentHashMap<UUID, CaseRuntime>()

    // ========================================
    // EntityService
    // ========================================

    override fun create(entity: Case): Case {
        require(findById(entity.id) == null) { "Duplicate entity id: ${entity.id}" }
        // Persist the full entity so client-supplied title and status are preserved
        //.
        val saved = caseRepository.save(entity)
        activeRuntimes[saved.id] = buildRuntime(saved)
        logger.info { "Case created: ${saved.id} for namespace ${entity.namespaceId}" }
        return saved
    }

    override fun update(entity: Case): Case {
        val current =
            findById(entity.id)
                ?: throw ResourceNotFoundException("Case not found: ${entity.id}")
        return if (entity.status != current.status) {
            // Route status changes through handleStatusChange so the runtime and
            // SSE clients stay consistent with the persisted state.
            handleStatusChange(entity.id, entity.status)
            // handleStatusChange already persisted the new status; return fresh view.
            findById(entity.id) ?: entity
        } else {
            caseRepository.save(entity)
        }
    }

    override fun findByIds(ids: Collection<UUID>): List<Case> = caseRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<Case> = caseRepository.findByParent(parentId)

    override fun findAccessibleByUserInNamespace(userId: UUID, namespaceId: UUID): List<Case> =
        caseRepository.findAccessibleByUserInNamespace(userId, namespaceId)

    override fun delete(id: UUID): Boolean {
        if (activeRuntimes.containsKey(id)) {
            killCase(id)
        }
        return caseRepository.delete(id)
    }

    override fun deleteByParent(parentId: UUID): Int {
        findByParent(parentId).forEach { killCase(it.id) }
        return caseRepository.deleteByParent(parentId)
    }

    // ========================================
    // Runtime lifecycle
    // ========================================

    override fun getCaseRuntime(caseId: UUID): CaseRuntime = activeRuntimes.computeIfAbsent(caseId) { rehydrate(it) }

    override fun findActiveRuntime(caseId: UUID): CaseRuntime? = activeRuntimes[caseId]

    /**
     * Rehydrates a [CaseRuntime] for a case that exists on disk but has no live
     * runtime instance (e.g. after a restart or reconnection to a past case).
     *
     * @throws ResourceNotFoundException if no persisted [Case] exists for [caseId]
     */
    private fun rehydrate(caseId: UUID): CaseRuntime {
        val case =
            caseRepository.findByIds(listOf(caseId)).firstOrNull()
                ?: throw ResourceNotFoundException("Case not found: $caseId")
        val pastEvents = caseEventService.findByParent(caseId)
        logger.info { "Rehydrating case $caseId with ${pastEvents.size} past events" }
        return buildRuntime(case, pastEvents)
    }

    /** Constructs a [CaseRuntime] wired with all service callbacks. */
    private fun buildRuntime(
        case: Case,
        inputEvents: List<CaseEvent> = emptyList(),
    ): CaseRuntime =
        CaseRuntime(
            id = case.id,
            namespaceId = case.namespaceId,
            updateStatus = { caseId, newStatus -> handleStatusChange(caseId, newStatus) },
            storeEvent = { event -> storeEvent(event) },
            selectAgent = { content, pastEvents -> selectAgent(content, pastEvents, case.namespaceId, case.id) },
            isAgentAuthorized = { agentName, userId ->
                userId == null || agentConfigService
                    .findAvailableByNamespaceIdAndUserId(namespaceId = case.namespaceId, userId = userId, agentName = agentName)
                    .isNotEmpty()
            },
            runAgent = { agentName, events, eventsProvider, userId, shouldContinue -> runAgent(agentName, case.id, events, eventsProvider, userId, shouldContinue) },
            inputEvents = inputEvents,
        )

    // ========================================
    // Message handling (called by controller)
    // ========================================

    override fun addMessage(
        caseId: UUID,
        actor: Actor,
        content: List<MessageContent>,
        answerToEventId: UUID?,
    ) {
        val runtime = getCaseRuntime(caseId)
        runtime.addUserMessage(actor, content, answerToEventId)
        // run() is self-guarding via an AtomicBoolean — launch unconditionally.
        scope.launch { runtime.run() }
    }

    // ========================================
    // Agent selection (business logic)
    // ========================================

    /**
     * Resolves which agent should handle a message and returns the ordered list of
     * events to store+emit on the runtime.
     *
     * Resolution order:
     * 1. Explicit `@mention` in the message content — resolved by name; on miss, falls
     *    back to the namespace default with a [WarnEvent].
     * 2. Last selected agent in this case (from [pastEvents]) — preserves continuity
     *    across turns. If the agent is no longer available (deleted, disabled), falls
     *    back to the namespace default with a [WarnEvent].
     * 3. Namespace default agent — [Namespace.defaultAgentName] resolved by name.
     *
     * Returns a single [WarnEvent] (no [AgentSelectedEvent]) when no default agent
     * is configured on the namespace, signalling the runtime to stop cleanly.
     *
     * @param content the message content to inspect for @mention syntax.
     * @param pastEvents the full event history of the case at the time of this call.
     */
    private fun selectAgent(
        content: List<MessageContent>,
        pastEvents: List<CaseEvent>,
        namespaceId: UUID,
        caseId: UUID,
    ): List<CaseEvent> {
        val mentionedName =
            content
                .filterIsInstance<MessageContent.Text>()
                .firstOrNull()
                ?.content
                ?.trim()
                ?.let { MENTION_REGEX.find(it)?.groupValues?.get(1) }

        val lastUserMessageIndex = pastEvents.indexOfLast { it is MessageEvent }
        val lastSelectedName =
            pastEvents
                .take(lastUserMessageIndex.coerceAtLeast(0))
                .filterIsInstance<AgentSelectedEvent>()
                .lastOrNull()
                ?.agentName

        return when {
            mentionedName != null -> {
                val resolvedName = agentService.resolveAgentName(mentionedName, namespaceId)
                when {
                    resolvedName != null -> {
                        logger.info { "Agent mention resolved: @$mentionedName -> $resolvedName" }
                        listOf(agentSelectedEvent(resolvedName, namespaceId, caseId))
                    }
                    else -> {
                        logger.warn { "Agent '@$mentionedName' not found, falling back to default" }
                        listOf(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "Agent '$mentionedName' not found")) +
                            selectDefaultAgent(namespaceId, caseId)
                    }
                }
            }
            lastSelectedName != null -> {
                val stillAvailable = agentService.resolveAgentName(lastSelectedName, namespaceId) != null
                when {
                    stillAvailable -> {
                        logger.info { "Re-using last selected agent: $lastSelectedName" }
                        listOf(agentSelectedEvent(lastSelectedName, namespaceId, caseId))
                    }
                    else -> {
                        logger.warn { "Last selected agent '$lastSelectedName' is no longer available, falling back to default" }
                        listOf(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "Agent '$lastSelectedName' is no longer available")) +
                            selectDefaultAgent(namespaceId, caseId)
                    }
                }
            }
            else -> selectDefaultAgent(namespaceId, caseId)
        }
    }

    /**
     * Resolves the namespace default agent by name from [Namespace.defaultAgentName].
     *
     * Returns a single [AgentSelectedEvent] when the default is configured and resolvable.
     * Returns a single [WarnEvent] (no [AgentSelectedEvent]) when:
     * - [Namespace.defaultAgentName] is null (no default configured)
     * - the configured name no longer matches any [AgentConfig] in the namespace
     *
     * In both error cases the runtime will stop cleanly on the [WarnEvent] with no
     * [AgentSelectedEvent] following it.
     */
    private fun selectDefaultAgent(
        namespaceId: UUID,
        caseId: UUID,
    ): List<CaseEvent> {
        val namespaceLevelDefault = namespaceService.findById(namespaceId)?.defaultAgentName
        val effectiveDefaultName = namespaceLevelDefault ?: agentConfigProperties.agentName

        return when {
            effectiveDefaultName == null -> {
                logger.warn { "No default agent configured for namespace $namespaceId" }
                listOf(
                    WarnEvent(
                        namespaceId = namespaceId,
                        caseId = caseId,
                        message = "No default agent configured for this namespace. Use @agentName to address an agent explicitly.",
                    ),
                )
            }
            else -> {
                val resolvedName = agentService.resolveAgentName(effectiveDefaultName, namespaceId)
                when {
                    resolvedName != null -> {
                        val source = if (namespaceLevelDefault != null) "namespace" else "environment"
                        logger.info { "Selecting $source default agent: $resolvedName" }
                        listOf(agentSelectedEvent(resolvedName, namespaceId, caseId))
                    }
                    else -> {
                        logger.warn { "Default agent '$effectiveDefaultName' is not available in namespace $namespaceId" }
                        listOf(
                            WarnEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                message = "Default agent '$effectiveDefaultName' is not available. Use @agentName to address an agent explicitly.",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun agentSelectedEvent(
        agentName: String,
        namespaceId: UUID,
        caseId: UUID,
    ) = AgentSelectedEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        agentId = UUID.nameUUIDFromBytes(agentName.toByteArray()),
        agentName = agentName,
    )

    // ========================================
    // Agent execution (business logic)
    // ========================================

    private suspend fun runAgent(
        agentName: String,
        caseId: UUID,
        events: List<CaseEvent>,
        eventsProvider: () -> List<CaseEvent>,
        userId: UUID?,
        shouldContinue: () -> Boolean,
    ) {
        val runtime = activeRuntimes[caseId] ?: throw ResourceNotFoundException("No active case runtime found: $caseId")

        requireNotNull(userId) {
            "Cannot run agent for case $caseId: no user identity found in event history"
        }
        requireNotNull(userService.findById(userId)) {
            "Cannot run agent for case $caseId: user $userId does not exist"
        }

        logger.info { "Running agent: $agentName for case $caseId" }
        val context = AgentExecutionContext(
            namespaceId = runtime.namespaceId,
            caseId = caseId,
            userId = userId,
            caseEventsProvider = eventsProvider,
        )
        agentService
            .findAgentByName(agentName, context)
            .run(events, shouldContinue)
            .catch { error ->
                logger.error(error) { "Error in agent $agentName for case $caseId" }
                storeEvent(
                    WarnEvent(
                        namespaceId = runtime.namespaceId,
                        caseId = caseId,
                        message = "Agent $agentName error: ${error.message}",
                    ),
                ).also { saved ->
                    runtime.emitEvent(saved)
                }
            }.collect { event ->
                val saved = storeEvent(event)
                if (event.caseId == caseId && event !is TransientCaseEvent) {
                    runtime.pushEvents(listOf(saved))
                }
                runtime.emitEvent(saved)
            }
        logger.info { "Agent $agentName finished for case $caseId" }
    }

    /**
     * Persists an event via [CaseEventService] and returns the saved copy.
     * Called by the runtime's [CaseRuntime.storeEvent] callback —
     * the runtime itself handles adding to its list and emitting on the SSE flow.
     *
     * [TextChunkEvent]s are streaming-only: they carry incremental text fragments
     * that are superseded by the final [io.whozoss.agentos.sdk.caseEvent.MessageEvent].
     * Persisting them would bloat the event store without adding any replay value,
     * so they are returned as-is without being written to the repository.
     */
    private fun storeEvent(event: CaseEvent): CaseEvent =
        when (event) {
            is TransientCaseEvent -> event
            else -> caseEventService.create(event)
        }

    // ========================================
    // Status transitions
    // ========================================

    /**
     * Persists the new status, emits a [CaseStatusEvent] to SSE clients,
     * and evicts the runtime when a terminal status is reached.
     *
     * The runtime is evicted *after* the status event is emitted so SSE clients
     * always receive the final status before the stream closes.
     */
    private fun handleStatusChange(
        caseId: UUID,
        newStatus: CaseStatus,
    ) {
        val case = getById(caseId)
        val oldStatus = case.status
        val updated = caseRepository.save(case.copy(status = newStatus))

        if (newStatus == CaseStatus.ERROR) {
            logger.error { "Case $caseId status: $oldStatus -> $newStatus" }
        } else {
            logger.info { "Case $caseId status: $oldStatus -> $newStatus" }
        }

        val statusEvent =
            CaseStatusEvent(
                metadata = EntityMetadata(),
                caseId = caseId,
                namespaceId = updated.namespaceId,
                status = newStatus,
            )
        val savedStatusEvent = caseEventService.create(statusEvent)

        // Emit the status event before eviction so SSE clients receive it.
        activeRuntimes[caseId]?.let {
            it.emitEvent(savedStatusEvent)
            if (newStatus.isTerminal()) {
                activeRuntimes.remove(caseId)
                logger.info { "Case $caseId reached terminal status $newStatus, evicted" }
            }
        }
    }

    // ========================================
    // Execution control
    // ========================================

    override fun getActiveCasesByNamespace(namespaceId: UUID): List<CaseRuntime> =
        activeRuntimes.values.filter { it.namespaceId == namespaceId }

    override fun getAllActiveCases(): List<CaseRuntime> = activeRuntimes.values.toList()

    override fun interruptCase(caseId: UUID) {
        val runtime =
            activeRuntimes[caseId]
                ?: throw ResourceNotFoundException("No active case runtime found: $caseId")
        logger.info { "Interrupting case: $caseId" }
        runtime.requestInterrupt()
    }

    override fun killCase(caseId: UUID) {
        logger.info { "Killing case: $caseId" }
        // Signal the runtime loop to exit cleanly if it is currently running,
        // then let handleStatusChange evict it via the isTerminal() path.
        activeRuntimes[caseId]?.requestKill()
        handleStatusChange(caseId, CaseStatus.KILLED)
    }

    // ========================================
    // Lifecycle
    // ========================================

    @PreDestroy
    fun shutdown() {
        logger.info { "Shutting down CaseService..." }
        activeRuntimes.keys.toList().forEach {
            try {
                killCase(it)
            } catch (e: Exception) {
                logger.warn(e) { "Error killing case $it during shutdown" }
            }
        }
        activeRuntimes.clear()
        scope.cancel()
        logger.info { "CaseService shutdown complete" }
    }

    companion object : KLogging() {
        /** Matches an `@mention` at the start of a trimmed message, e.g. `@my-agent`. */
        private val MENTION_REGEX = """^@(\S+)""".toRegex()
    }
}

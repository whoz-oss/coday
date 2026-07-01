package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseEvent.lastUserIdOrNull
import io.whozoss.agentos.delegation.SubCaseLauncher
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.security.access.prepost.PreAuthorize
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
    private val caseConfig: CaseConfigProperties,
    private val permissionService: PermissionService,
) : CaseService,
    SubCaseLauncher {
    private val idleEvictionGraceMs get() = caseConfig.idleEvictionGraceMs

    /**
     * Coroutine scope used to run case execution loops in the background.
     * Each [run] call is launched here so HTTP threads are never blocked.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeRuntimes = ConcurrentHashMap<UUID, CaseRuntime>()

    /**
     * Eviction watcher [Job] per active runtime, keyed by case id.
     * Cancelled whenever the runtime leaves [activeRuntimes] (terminal status or eviction).
     */
    private val watcherJobs = ConcurrentHashMap<UUID, Job>()

    // ======================================================
    // EntityService
    // ======================================================

    override fun create(entity: Case): Case {
        require(findById(entity.id) == null) { "Duplicate entity id: ${entity.id}" }
        // Persist the full entity so client-supplied title and status are preserved
        // .
        val saved = caseRepository.save(entity)
        activeRuntimes[saved.id] = buildRuntime(saved)
        logger.info { "Case created: ${saved.id} for namespace ${entity.namespaceId}" }
        // Watcher is started inside buildRuntime via .also { startEvictionWatcher(...) }
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

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Case> = caseRepository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Case> = caseRepository.findByParent(parentId)

    override fun findAccessibleByUserInNamespace(
        userId: UUID,
        namespaceId: UUID,
    ): List<Case> = caseRepository.findAccessibleByUserInNamespace(userId, namespaceId)

    @PreAuthorize("hasRole('SUPER_ADMIN') or #userId.toString() == authentication.name")
    override fun findConcerningUser(userId: UUID): List<Case> = caseRepository.findConcerningUser(userId)

    @PreAuthorize("hasRole('SUPER_ADMIN') or #userId.toString() == authentication.name")
    override fun findConcerningUserInNamespace(
        userId: UUID,
        namespaceId: UUID,
    ): List<Case> = caseRepository.findConcerningUserInNamespace(userId, namespaceId)

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

    // ======================================================
    // Runtime lifecycle
    // ======================================================

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
        // Watcher is started inside buildRuntime via .also { startEvictionWatcher(...) }
    }

    /**
     * Starts a long-lived eviction watcher for [runtime].
     *
     * Combines [CaseRuntime.subscriptionCount] and [CaseRuntime.statusFlow] into a
     * single signal: eviction is only triggered when **both** conditions hold at the
     * same time — the case is [CaseStatus.IDLE] **and** no SSE subscribers are connected.
     *
     * This avoids the race where a client disconnects while the agent is still running:
     * `subscriptionCount` would fall to 0 during [CaseStatus.RUNNING], but `combine`
     * emits `false` for that state and the grace period never starts.
     *
     * On each transition to `(IDLE, 0)`, the watcher:
     * 1. Waits [idleEvictionGraceMs] to absorb brief reconnects (e.g. page refresh,
     *    or a user typing a quick follow-up).
     * 2. Re-checks the combined condition — if a subscriber reconnected or a new
     *    message arrived (status back to RUNNING) during the grace period, eviction
     *    is skipped.
     * 3. If both conditions still hold, evicts the runtime.
     *
     * The coroutine runs for the lifetime of the service scope. It is automatically
     * cancelled when [scope] is cancelled (service shutdown).
     */
    private fun startEvictionWatcher(
        caseId: UUID,
        runtime: CaseRuntime,
    ) {
        logger.trace { "Case $caseId eviction watcher started" }
        val job =
            scope.launch {
                combine(runtime.subscriptionCount, runtime.statusFlow) { count, status ->
                    count == 0 && status == CaseStatus.IDLE
                }
                    // Grace period: absorb brief reconnects and fast follow-up messages.
                    .debounce(idleEvictionGraceMs)
                    .filter { it }
                    .collect {
                        // Re-check: a subscriber may have reconnected or a new message may
                        // have arrived (status back to RUNNING) during the grace period.
                        if (activeRuntimes.containsKey(caseId) &&
                            runtime.subscriptionCount.value == 0 &&
                            runtime.statusFlow.value == CaseStatus.IDLE
                        ) {
                            activeRuntimes.remove(caseId)
                            // Remove from watcherJobs without cancelling: we are executing
                            // inside this very coroutine, so cancel() would be a no-op and
                            // is semantically misleading. The coroutine ends naturally here.
                            watcherJobs.remove(caseId)
                            logger.info { "Case $caseId: evicted idle runtime (no SSE subscribers after ${idleEvictionGraceMs}ms grace)" }
                        } else {
                            logger.debug {
                                "Case $caseId: eviction skipped — " +
                                    "status=${runtime.statusFlow.value}, subscribers=${runtime.subscriptionCount.value}"
                            }
                        }
                    }
            }
        watcherJobs[caseId] = job
    }

    /** Constructs a [CaseRuntime] wired with all service callbacks. */
    private fun buildRuntime(
        case: Case,
        inputEvents: List<CaseEvent> = emptyList(),
    ): CaseRuntime =
        CaseRuntime(
            id = case.id,
            namespaceId = case.namespaceId,
            updateStatusCallback = { caseId, newStatus -> handleStatusChange(caseId, newStatus) },
            storeEvent = { event -> storeEvent(event) },
            selectAgent = { content, pastEvents -> selectAgent(content, pastEvents, case.namespaceId, case.id) },
            isAgentAuthorized = { agentName, userId ->
                userId == null ||
                    agentConfigService
                        .findAvailableByNamespaceIdAndUserId(
                            namespaceId = case.namespaceId,
                            userId = userId,
                            agentName = agentName,
                        ).isNotEmpty()
            },
            runAgent = { agentName, events, eventsProvider, userId, shouldContinue ->
                runAgent(
                    agentName,
                    case.id,
                    events,
                    eventsProvider,
                    userId,
                    shouldContinue,
                )
            },
            inputEvents = inputEvents,
            initialStatus = case.status,
        ).also { startEvictionWatcher(case.id, it) }

    // ======================================================
    // Message handling (called by controller)
    // ======================================================

    override fun addMessage(
        caseId: UUID,
        actor: Actor,
        content: List<MessageContent>,
        answerToEventId: UUID?,
        sessionContext: Map<String, Any?>?,
    ) {
        val runtime = getCaseRuntime(caseId)
        runtime.addUserMessage(actor, content, answerToEventId, sessionContext)
        // run() is self-guarding via an AtomicBoolean — launch unconditionally.
        scope.launch { runtime.run() }
    }

    // ======================================================
    // Agent selection (business logic)
    // ======================================================

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
     * 3. Namespace default agent — [io.whozoss.agentos.namespace.Namespace.defaultAgentName] resolved by name.
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
        val userId = pastEvents.lastUserIdOrNull()
        val lastSelectedName =
            pastEvents
                .take(lastUserMessageIndex.coerceAtLeast(0))
                .filterIsInstance<AgentSelectedEvent>()
                .lastOrNull()
                ?.agentName

        return when {
            mentionedName != null -> {
                val resolvedName =
                    agentService.resolveAgentName(
                        namePart = mentionedName,
                        namespaceId = namespaceId,
                        userId = userId,
                    )
                when {
                    resolvedName != null -> {
                        logger.info { "Agent mention resolved: @$mentionedName -> $resolvedName" }
                        listOf(agentSelectedEvent(resolvedName, namespaceId, caseId))
                    }

                    else -> {
                        logger.warn { "Agent '@$mentionedName' not found or not accessible, falling back to default" }
                        listOf(
                            WarnEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                message = "Agent '$mentionedName' not found or not accessible",
                            ),
                        ) +
                            selectDefaultAgent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                userId = userId,
                            )
                    }
                }
            }

            lastSelectedName != null -> {
                val stillAvailable =
                    agentService.resolveAgentName(
                        namePart = lastSelectedName,
                        namespaceId = namespaceId,
                        userId = userId,
                    ) != null
                when {
                    stillAvailable -> {
                        logger.info { "Re-using last selected agent: $lastSelectedName" }
                        listOf(agentSelectedEvent(lastSelectedName, namespaceId, caseId))
                    }

                    else -> {
                        logger.warn { "Last selected agent '$lastSelectedName' is no longer available, falling back to default" }
                        listOf(
                            WarnEvent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                message = "Agent '$lastSelectedName' is no longer available",
                            ),
                        ) +
                            selectDefaultAgent(
                                namespaceId = namespaceId,
                                caseId = caseId,
                                userId = userId,
                            )
                    }
                }
            }

            else -> {
                selectDefaultAgent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    userId = userId,
                )
            }
        }
    }

    /**
     * Resolves the namespace default agent by name from [io.whozoss.agentos.namespace.Namespace.defaultAgentName].
     *
     * Returns a single [AgentSelectedEvent] when the default is configured and resolvable.
     * Returns a single [WarnEvent] (no [AgentSelectedEvent]) when:
     * - [io.whozoss.agentos.namespace.Namespace.defaultAgentName] is null (no default configured)
     * - the configured name no longer matches any [io.whozoss.agentos.agentConfig.AgentConfig] in the namespace
     *
     * In both error cases the runtime will stop cleanly on the [WarnEvent] with no
     * [AgentSelectedEvent] following it.
     */
    private fun selectDefaultAgent(
        namespaceId: UUID,
        caseId: UUID,
        userId: UUID?,
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
                val resolvedName =
                    agentService.resolveAgentName(
                        namePart = effectiveDefaultName,
                        namespaceId = namespaceId,
                        userId = userId,
                    )
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
                                message =
                                    "Default agent '$effectiveDefaultName' is not available. " +
                                        "Use @agentName to address an agent explicitly.",
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

    // ======================================================
    // Agent execution (business logic)
    // ======================================================

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
        val context =
            AgentExecutionContext(
                namespaceId = runtime.namespaceId,
                caseId = caseId,
                userId = userId,
                caseEventsProvider = eventsProvider,
            )
        val agent = agentService.findAgentByName(agentName, context, this)

        if (shouldEmitRunningEvent(events)) {
            val runningEvent =
                AgentRunningEvent(
                    namespaceId = runtime.namespaceId,
                    caseId = caseId,
                    agentId = agent.id,
                    agentName = agent.name,
                    llmProvider = agent.llmProvider,
                    llmModel = agent.llmModel,
                )
            storeEvent(runningEvent).also { saved ->
                runtime.pushEvents(listOf(saved))
                runtime.emitEvent(saved)
            }
        }

        agent
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

    // ======================================================
    // Status transitions
    // ======================================================

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
                watcherJobs.remove(caseId)?.cancel()
                logger.info { "Case $caseId reached terminal status $newStatus, evicted" }
            }
        }
    }

    // ======================================================
    // Execution control
    // ======================================================

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

    override fun killSubCase(caseId: UUID) = killCase(caseId)

    override fun resumeSubCase(
        subCaseId: UUID,
        agentName: String,
        task: String,
        userId: UUID,
        allowedAgents: List<String>,
    ): CaseRuntime {
        val subCase =
            findById(subCaseId)
                ?: throw ResourceNotFoundException("Sub-case not found: $subCaseId")
        require(subCase.status == io.whozoss.agentos.sdk.caseFlow.CaseStatus.IDLE) {
            "Sub-case $subCaseId is in status ${subCase.status}, expected IDLE to resume."
        }
        require(agentName in allowedAgents) {
            "Agent '$agentName' is not in the delegation allowlist for sub-case $subCaseId."
        }
        val user =
            userService.findById(userId)
                ?: throw ResourceNotFoundException("User not found: $userId")
        val displayName =
            listOfNotNull(user.firstname, user.lastname)
                .joinToString(" ")
                .ifBlank { userId.toString() }
        val actor =
            Actor(
                id = userId.toString(),
                displayName = displayName,
                role = ActorRole.USER,
            )
        val runtime = getCaseRuntime(subCaseId)
        runtime.addUserMessage(actor, listOf(MessageContent.Text("@$agentName $task")))
        scope.launch { runtime.run() }
        logger.info { "Sub-case $subCaseId resumed, agent=$agentName" }
        return runtime
    }

    override fun killCase(caseId: UUID) {
        logger.info { "Killing case: $caseId" }
        // Propagate kill to all direct sub-cases first (depth-first recursion).
        // Each recursive call will in turn kill its own sub-cases, so arbitrarily
        // deep delegation chains are handled without any extra tracking.
        caseRepository.findActiveByParentCaseId(caseId).forEach { subCase ->
            logger.info { "Killing sub-case ${subCase.id} (parent: $caseId)" }
            killCase(subCase.id)
        }
        // Signal the runtime loop to exit cleanly if it is currently running,
        // then let handleStatusChange evict it via the isTerminal() path.
        activeRuntimes[caseId]?.requestKill()
        handleStatusChange(caseId, CaseStatus.KILLED)
    }

    override fun startSubCase(
        parentCaseId: UUID,
        namespaceId: UUID,
        agentName: String,
        task: String,
        userId: UUID,
    ): CaseRuntime {
        val ancestorDepth = caseRepository.countAncestorDepth(parentCaseId)
        if (ancestorDepth >= MAX_DELEGATION_DEPTH) {
            throw IllegalStateException(
                "Delegation depth limit ($MAX_DELEGATION_DEPTH) reached for case $parentCaseId. " +
                    "Cannot create a sub-case at depth ${ancestorDepth + 1}.",
            )
        }

        val user =
            userService.findById(userId)
                ?: throw ResourceNotFoundException("User not found: $userId")
        val displayName =
            listOfNotNull(user.firstname, user.lastname)
                .joinToString(" ")
                .ifBlank { userId.toString() }
        val actor =
            Actor(
                id = userId.toString(),
                displayName = displayName,
                role = ActorRole.USER,
            )
        // Use @mention syntax so the normal selectAgent resolution picks up the
        // requested agent without any special-casing in the runtime.
        val mentionedTask = "@$agentName $task"
        val subCase =
            create(
                Case(
                    namespaceId = namespaceId,
                    title = "Sub-case: $task".take(120),
                    parentCaseId = parentCaseId,
                ),
            )

        // Grant the delegating user ADMIN on the sub-case so they can list,
        // open, and stream it — same grant that CaseController.create applies
        // for user-created cases.
        runCatching {
            permissionService.grantPermission(
                userId.toString(),
                EntityType.CASE,
                subCase.id.toString(),
                PermissionRelation.ADMIN,
            )
        }.onFailure { e ->
            logger.warn(e) {
                "Auto-ADMIN grant failed for sub-case ${subCase.id} (user $userId) — sub-case persisted. " +
                    "Recovery: a super-admin or namespace ADMIN must grant ADMIN on the case manually."
            }
        }

        // Create the [:PARENT_OF] graph edge so countAncestorDepth can traverse the chain.
        runCatching {
            caseRepository.linkParentToChild(parentCaseId, subCase.id)
        }.onFailure { e ->
            logger.warn(e) { "Failed to link parent $parentCaseId -> child ${subCase.id} in Neo4j" }
        }

        val runtime = activeRuntimes[subCase.id]!!
        runtime.addUserMessage(actor, listOf(MessageContent.Text(mentionedTask)))
        scope.launch { runtime.run() }
        logger.info { "Sub-case ${subCase.id} started under parent $parentCaseId, agent=$agentName (depth=${ancestorDepth + 1})" }
        return runtime
    }

    // ======================================================
    // Lifecycle
    // ======================================================

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

    /**
     * Determines whether a new [AgentRunningEvent] should be emitted by inspecting
     * the event history.
     *
     * Returns `true` when the most recent [AgentSelectedEvent] is newer than the most
     * recent [AgentRunningEvent] (or when no [AgentRunningEvent] exists yet) — this is
     * the normal fresh-run path.
     *
     * Returns `false` when [AgentRunningEvent] is already the most recent of the two —
     * the case is rehydrating from a crash and emitting a duplicate would cause
     * [CaseRuntime.processNextStep] to re-trigger execution indefinitely.
     *
     * This comparison is safe because agent execution is strictly sequential within a
     * case: [CaseRuntime.run] is guarded by an [AtomicBoolean] that prevents concurrent
     * invocations, and [runAgent] suspends in [Flow.collect] until the agent's flow
     * terminates. No two agents can overlap, so the relative positions of
     * [AgentSelectedEvent] and [AgentRunningEvent] in the event list are always
     * well-ordered.
     */
    private fun shouldEmitRunningEvent(events: List<CaseEvent>): Boolean {
        val lastSelectedIndex = events.indexOfLast { it is AgentSelectedEvent }
        val lastRunningIndex = events.indexOfLast { it is AgentRunningEvent }
        return lastRunningIndex < 0 || lastSelectedIndex > lastRunningIndex
    }

    companion object : KLogging() {
        /**
         * Maximum depth of delegation chains allowed.
         *
         * A top-level case has depth 0. Its direct sub-case has depth 1, etc.
         * If a parent case is already at depth [MAX_DELEGATION_DEPTH] − 1, creating
         * a child would reach the limit and is rejected. This prevents runaway
         * delegation chains without forbidding legitimate multi-level orchestration.
         */
        private const val MAX_DELEGATION_DEPTH = 5

        /**
         * Matches an `@mention` at the start of a trimmed message, e.g. `@my-agent`.
         *
         * Agent names may contain letters, digits, hyphens and underscores only.
         * Using `\S+` was too broad: a message like `@inspector https://...` would
         * capture the entire `inspector https://...` string when the separator is a
         * non-breaking space (U+00A0) or any other non-ASCII whitespace character,
         * because `\S` in Java/Kotlin regex only excludes ASCII whitespace by default.
         * The tighter character class `[\w-]+` stops at the first space-like or
         * special character, ensuring only the agent name token is captured.
         */
        private val MENTION_REGEX = """^@([\w-]+)""".toRegex()
    }
}

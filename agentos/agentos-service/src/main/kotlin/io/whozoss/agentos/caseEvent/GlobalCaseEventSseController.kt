package io.whozoss.agentos.caseEvent

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateBroadcaster
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KLogging
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * SSE endpoint fanning in events from every active case belonging to the current user
 * into a single stream, filtered to the event types a notification client cares about.
 *
 * Tagged "sse" to be excluded from OpenAPI generation (ng-openapi-gen.json).
 * Clients should use the browser EventSource API, not a regular HTTP client.
 *
 * Deliberately separate from [CaseEventSseController]: no history replay, N concurrent
 * per-case collectors instead of one, a periodic reconciliation loop with no equivalent
 * in the per-case stream, and an extra [UserService] dependency the per-case controller
 * has no other reason to take on.
 */
@Tag(name = "sse", description = "Server-Sent Events endpoints — use EventSource API, not HTTP client")
@RestController
@RequestMapping("/api/cases")
class GlobalCaseEventSseController(
    private val caseService: CaseService,
    private val userService: UserService,
    private val caseConfig: CaseConfigProperties,
    private val activeCompanionSessionRegistry: ActiveCompanionSessionRegistry,
    private val namespaceService: NamespaceService,
    private val caseListUpdateBroadcaster: NamespaceCaseListUpdateBroadcaster,
) {
    private val heartbeatIntervalMs get() = caseConfig.sseHeartbeatIntervalMs
    private val pollIntervalMs get() = caseConfig.globalEventsPollIntervalMs

    /**
     * Stream notifiable events across all of the current user's active cases via SSE.
     *
     * GET /api/cases/events/mine
     *
     * No history replay — a fresh connection only sees events from the moment it
     * connects onward. Each frame carries the same shape as the per-case stream
     * ([CaseEventSseController]); every [CaseEvent] already carries its own `caseId`,
     * so a consumer can always tell which case an event belongs to.
     */
    @Operation(
        tags = ["sse"],
        summary = "Stream notifiable events across all of my active cases via SSE",
        description =
            "Server-Sent Events stream fanning in PendingConfirmationEvent, ConfirmationResolvedEvent, " +
                "QuestionEvent, AnswerEvent, AgentFinishedEvent and ErrorEvent across every case the current " +
                "user is directly related to and that is currently active. " +
                "It also carries a 'namespace-case-list-updated' event, payload '{\"namespaceId\":\"...\"}', " +
                "for every namespace the user can read: reload GET /api/cases/by-parentId/{namespaceId}/mine " +
                "when it arrives, instead of opening a stream per namespace. " +
                "Use the browser EventSource API to consume this endpoint, not a regular HTTP client.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description =
                    "SSE stream — each event is a JSON-serialized CaseEvent subtype " +
                        "(PendingConfirmationEvent, QuestionEvent, AgentFinishedEvent, etc.) " +
                        "with a \"type\" discriminant field and its own \"caseId\".",
                content = [Content(mediaType = "text/event-stream")],
            ),
        ],
    )
    @GetMapping("/events/mine", produces = ["text/event-stream"])
    @PreAuthorize("isAuthenticated()")
    fun streamMyEvents(): SseEmitter {
        val user = userService.getCurrentUser()
        val userId = user.id
        val authentication = AgentOsAuthentication(user)
        logger.info { "Client connecting to global event stream for user: $userId" }

        val emitter = SseEmitter(0L) // Infinite timeout
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val childJobs = mutableMapOf<UUID, Job>() // mutated only from the reconciliation coroutine

        // While this connection is open, the push dispatcher skips this user — they're
        // already watching events live here, a duplicate OS push would be noise.
        activeCompanionSessionRegistry.markConnected(userId)

        startCaseListCollectors(scope, user, authentication, emitter)
        startReconciliationJob(scope, userId, authentication, emitter, childJobs)
        startHeartbeatJob(scope, emitter, userId)

        emitter.onCompletion {
            logger.debug { "Global SSE emitter completed for user $userId" }
            activeCompanionSessionRegistry.markDisconnected(userId)
            scope.cancel()
        }

        emitter.onTimeout {
            logger.debug { "Global SSE emitter timed out for user $userId" }
            activeCompanionSessionRegistry.markDisconnected(userId)
            scope.cancel()
        }

        emitter.onError { throwable ->
            logger.debug { "Global SSE emitter error for user $userId: ${throwable.message}" }
            activeCompanionSessionRegistry.markDisconnected(userId)
            scope.cancel()
        }

        logger.info { "Global SSE connection established for user: $userId" }
        return emitter
    }

    /**
     * Periodically re-scans the user's visible+active case set and reconciles child
     * collector jobs: starts one for each newly-appeared case, cancels one for each case
     * that dropped out (ended, evicted, or no longer accessible). Cases present in both
     * the previous and new set are left completely untouched.
     *
     * Runs as a single sequential coroutine per connection, so [childJobs] needs no
     * synchronization — there is nothing else that could race against it.
     */
    private fun startReconciliationJob(
        scope: CoroutineScope,
        userId: UUID,
        authentication: Authentication,
        emitter: SseEmitter,
        childJobs: MutableMap<UUID, Job>,
    ) {
        scope.launch {
            while (isActive) {
                try {
                    val concerningCases =
                        withAuthentication(authentication) {
                            caseService.findConcerningUser(userId)
                        }
                    val visibleRuntimes =
                        resolveVisibleActiveRuntimes(
                            concerningCases,
                            caseService.getAllActiveCases(),
                        )
                    val visibleIds = visibleRuntimes.map { it.id }.toSet()

                    // Dropped out: cancel only that case's collector, nothing else.
                    (childJobs.keys - visibleIds).forEach { staleId ->
                        childJobs.remove(staleId)?.cancel()
                    }

                    // Newly appeared: launch a collector. Already-tracked ids are untouched.
                    visibleRuntimes.forEach { runtime ->
                        if (runtime.id !in childJobs) {
                            childJobs[runtime.id] = startCaseCollectorJob(scope, runtime, emitter)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Error reconciling active case set for user $userId" }
                }
                // Work runs before the delay so the initial set is live immediately on connect.
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Starts one collector per namespace the user can read, so that this stream also carries the
     * "reload your case list" signal of [NamespaceCaseListUpdateSseController].
     *
     * A browser allows 6 HTTP/1.1 connections per origin, shared by every tab, and a per-namespace
     * stream spent one of them in each open tab (#1616). Carrying the signal here costs nothing on
     * the wire: this stream is already open, and one tab holds it for the whole browser.
     *
     * Resolved once, at connect, like the per-namespace endpoint resolved its own permission:
     * [io.whozoss.agentos.permissions.PermissionService.listEntitiesForUser] answers an empty list
     * when the store fails, so re-resolving on a loop would read one slow Neo4j round-trip as
     * "access revoked" and cancel every collector, dropping the invalidations published meanwhile.
     * A namespace shared with the user later is picked up on the next connection.
     *
     * Super-admins hold no MEMBER edge: the endpoint this replaces went through hasPermission,
     * which bypasses for them, so the same branch as [NamespaceController] is needed here.
     */
    private fun startCaseListCollectors(
        scope: CoroutineScope,
        user: User,
        authentication: Authentication,
        emitter: SseEmitter,
    ) {
        scope.launch {
            val namespaceIds =
                withAuthentication(authentication) {
                    if (user.isAdmin) {
                        namespaceService.findAll().map { it.id }
                    } else {
                        namespaceService.findIdsVisibleTo(user.id.toString(), Action.READ)
                    }
                }
            namespaceIds.distinct().forEach { namespaceId ->
                startCaseListCollectorJob(scope, namespaceId, emitter)
            }
        }
    }

    private fun startCaseListCollectorJob(
        scope: CoroutineScope,
        namespaceId: UUID,
        emitter: SseEmitter,
    ): Job =
        scope.launch {
            try {
                caseListUpdateBroadcaster.events(namespaceId).collect {
                    // The namespace travels in the payload: on this stream, unlike the per-namespace
                    // one, the URL does not say which list went stale.
                    emitter.send(
                        SseEmitter
                            .event()
                            .name(CASE_LIST_UPDATED_CHANNEL)
                            .data(mapOf("namespaceId" to namespaceId.toString())),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Completing the emitter is what lets Spring fire onCompletion, and with it
                // markDisconnected: cancelling the scope alone kills the heartbeat too, so nothing
                // would ever write to the socket again and the user would stay marked connected.
                logger.debug { "Failed to send the case-list update of namespace $namespaceId: ${e.message}" }
                emitter.completeWithError(e)
                scope.cancel()
            }
        }

    private fun startCaseCollectorJob(
        scope: CoroutineScope,
        runtime: CaseRuntime,
        emitter: SseEmitter,
    ): Job =
        scope.launch {
            try {
                runtime.events.collect { event ->
                    if (event.isNotifiable()) sendEvent(event, emitter)
                }
            } catch (e: CancellationException) {
                // Normal path: reconciliation cancelled this specific case's collector
                // (case ended/no longer visible), or the client disconnected. Must be
                // rethrown, never treated as a write failure below — otherwise a routine
                // per-case cancel would tear down the whole multi-case stream.
                throw e
            } catch (e: Exception) {
                // Write failed — socket is dead. Complete the emitter so that onCompletion runs
                // markDisconnected, then tear down the whole connection (all collectors + poll loop
                // + heartbeat share this scope).
                logger.debug { "Failed to send event for case ${runtime.id}: ${e.message}" }
                emitter.completeWithError(e)
                scope.cancel()
            }
        }

    private fun sendEvent(
        event: CaseEvent,
        emitter: SseEmitter,
    ) = emitter.send(
        SseEmitter
            .event()
            .id(event.id.toString())
            .name(event.type.value)
            .data(event),
    )

    private fun startHeartbeatJob(
        scope: CoroutineScope,
        emitter: SseEmitter,
        userId: UUID,
    ): Job =
        scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                try {
                    // SSE comment — ignored by EventSource but forces a socket write.
                    emitter.send(
                        SseEmitter
                            .event()
                            .comment("keep-alive"),
                    )
                } catch (e: Exception) {
                    logger.debug { "Heartbeat write failed for user $userId — client likely disconnected" }
                    scope.cancel()
                }
            }
        }

    companion object : KLogging() {
        /** Same event name as the per-namespace stream, so a client reads one protocol, not two. */
        const val CASE_LIST_UPDATED_CHANNEL = "namespace-case-list-updated"
    }
}

/**
 * Installs [authentication] into [SecurityContextHolder] on the current background thread
 * for the duration of [block], restoring the previous authentication in `finally` so
 * `@PreAuthorize` on [CaseService.findConcerningUser] remains enforced without thread-local leaks.
 */
internal inline fun <T> withAuthentication(
    authentication: Authentication,
    block: () -> T,
): T {
    val previous = SecurityContextHolder.getContext().authentication
    SecurityContextHolder.getContext().authentication = authentication
    return try {
        block()
    } finally {
        if (previous == null) {
            SecurityContextHolder.clearContext()
        } else {
            SecurityContextHolder.getContext().authentication = previous
        }
    }
}

/** Cases that are both concerning [concerningCases]'s owner and currently live in [activeRuntimes]. */
internal fun resolveVisibleActiveRuntimes(
    concerningCases: List<Case>,
    activeRuntimes: List<CaseRuntime>,
): List<CaseRuntime> {
    val concerningIds = concerningCases.map { it.id }.toSet()
    return activeRuntimes.filter { it.id in concerningIds }
}

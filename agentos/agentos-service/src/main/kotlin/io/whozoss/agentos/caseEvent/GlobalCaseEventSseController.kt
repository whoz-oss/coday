package io.whozoss.agentos.caseEvent

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
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
        val userId = userService.getCurrentUser().id
        logger.info { "Client connecting to global event stream for user: $userId" }

        val emitter = SseEmitter(0L) // Infinite timeout
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val childJobs = mutableMapOf<UUID, Job>() // mutated only from the reconciliation coroutine

        // While this connection is open, the push dispatcher skips this user — they're
        // already watching events live here, a duplicate OS push would be noise.
        activeCompanionSessionRegistry.markConnected(userId)

        startReconciliationJob(scope, userId, emitter, childJobs)
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
        emitter: SseEmitter,
        childJobs: MutableMap<UUID, Job>,
    ) {
        scope.launch {
            while (isActive) {
                try {
                    val visibleRuntimes =
                        resolveVisibleActiveRuntimes(
                            caseService.findConcerningUser(userId),
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
                // Write failed — socket is dead. Tear down the whole connection (all
                // collectors + poll loop + heartbeat share this scope), same failure
                // path as the heartbeat's below.
                logger.debug { "Failed to send event for case ${runtime.id}: ${e.message}" }
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

    companion object : KLogging()
}

/** Cases that are both concerning [concerningCases]'s owner and currently live in [activeRuntimes]. */
internal fun resolveVisibleActiveRuntimes(
    concerningCases: List<Case>,
    activeRuntimes: List<CaseRuntime>,
): List<CaseRuntime> {
    val concerningIds = concerningCases.map { it.id }.toSet()
    return activeRuntimes.filter { it.id in concerningIds }
}

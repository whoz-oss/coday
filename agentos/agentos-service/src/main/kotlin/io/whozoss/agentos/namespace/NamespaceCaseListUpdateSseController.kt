package io.whozoss.agentos.namespace

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.agentos.caseFlow.CaseConfigProperties
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Authenticated namespace stream that tells clients to reload their permission-filtered case list. */
@Tag(name = "sse", description = "Server-Sent Events endpoints — use EventSource API, not HTTP client")
@RestController
@RequestMapping("/api/namespaces")
class NamespaceCaseListUpdateSseController(
    private val broadcaster: NamespaceCaseListUpdateBroadcaster,
    private val caseConfig: CaseConfigProperties,
    private val parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val heartbeatIntervalMs get() = caseConfig.sseHeartbeatIntervalMs

    @Operation(
        tags = ["sse"],
        summary = "Stream namespace case-list updates via SSE",
        description =
            "Each 'namespace-case-list-updated' event carries the neutral JSON payload '{}'. " +
                "Reload GET /api/cases/by-parentId/{namespaceId}/mine when it arrives or when the stream reconnects.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "SSE stream of namespace case-list updates carrying data: {}.",
                content = [Content(mediaType = "text/event-stream")],
            ),
        ],
    )
    @GetMapping("/{namespaceId}/case-list-updates", produces = ["text/event-stream"])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun streamCaseListUpdates(@PathVariable namespaceId: UUID): SseEmitter {
        val emitter = SseEmitter(0L)
        val connectionJob = SupervisorJob(parentScope.coroutineContext[Job])
        val connectionScope = CoroutineScope(parentScope.coroutineContext + connectionJob)
        val cleaned = AtomicBoolean(false)
        var jobs: Pair<Job, Job>? = null
        val cleanup = {
            if (cleaned.compareAndSet(false, true)) {
                jobs?.first?.cancel()
                jobs?.second?.cancel()
                connectionJob.cancel()
            }
        }

        jobs = startConnectionJobs(connectionScope, emitter, namespaceId, cleanup)
        emitter.onCompletion(cleanup)
        emitter.onTimeout(cleanup)
        emitter.onError { cleanup() }
        return emitter
    }

    internal fun startConnectionJobs(
        scope: CoroutineScope,
        emitter: SseEmitter,
        namespaceId: UUID,
        cleanup: () -> Unit,
    ): Pair<Job, Job> {
        val updateJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    broadcaster.events(namespaceId).collect {
                        emitter.send(
                            SseEmitter
                                .event()
                                .id(sequence.incrementAndGet().toString())
                                .name(NAMESPACE_CASE_LIST_UPDATED_EVENT)
                                .data(NEUTRAL_PAYLOAD),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    logger.debug { "Namespace case-list update stream closed for $namespaceId: ${error.message}" }
                    emitter.completeWithError(error)
                } finally {
                    cleanup()
                }
            }
        val heartbeatJob =
            scope.launch {
                try {
                    while (isActive) {
                        delay(heartbeatIntervalMs)
                        emitter.send(SseEmitter.event().comment("keep-alive"))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    logger.debug { "Namespace heartbeat failed for $namespaceId — client likely disconnected" }
                    emitter.completeWithError(error)
                } finally {
                    cleanup()
                }
            }
        return updateJob to heartbeatJob
    }

    companion object : KLogging() {
        const val NAMESPACE_CASE_LIST_UPDATED_EVENT = "namespace-case-list-updated"
        const val NEUTRAL_PAYLOAD = "{}"
        private val sequence = AtomicLong()
    }
}

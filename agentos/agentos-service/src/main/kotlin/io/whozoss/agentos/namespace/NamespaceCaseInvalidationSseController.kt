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
import java.util.concurrent.atomic.AtomicLong

/** Authenticated namespace stream that tells clients to reload their permission-filtered case list. */
@Tag(name = "sse", description = "Server-Sent Events endpoints — use EventSource API, not HTTP client")
@RestController
@RequestMapping("/api/namespaces")
class NamespaceCaseInvalidationSseController(
    private val broadcaster: NamespaceCaseInvalidationBroadcaster,
    private val caseConfig: CaseConfigProperties,
) {
    private val heartbeatIntervalMs get() = caseConfig.sseHeartbeatIntervalMs

    @Operation(
        tags = ["sse"],
        summary = "Stream namespace case-list invalidations via SSE",
        description =
            "Each 'namespace-case-invalidation' event has no data payload. " +
                "Reload GET /api/cases/by-parentId/{namespaceId}/mine when it arrives or when the stream reconnects.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "SSE stream of payload-free namespace case-list invalidations.",
                content = [Content(mediaType = "text/event-stream")],
            ),
        ],
    )
    @GetMapping("/{namespaceId}/case-invalidation", produces = ["text/event-stream"])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun streamCaseInvalidations(@PathVariable namespaceId: UUID): SseEmitter {
        val emitter = SseEmitter(0L)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        startInvalidationJob(scope, emitter, namespaceId)
        startHeartbeatJob(scope, emitter, namespaceId)

        emitter.onCompletion { scope.cancel() }
        emitter.onTimeout { scope.cancel() }
        emitter.onError { scope.cancel() }
        return emitter
    }

    private fun startInvalidationJob(
        scope: CoroutineScope,
        emitter: SseEmitter,
        namespaceId: UUID,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                broadcaster.events(namespaceId).collect {
                    emitter.send(
                        SseEmitter
                            .event()
                            .id(sequence.incrementAndGet().toString())
                            .name(NAMESPACE_CASE_INVALIDATION_EVENT),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                logger.debug { "Namespace case invalidation stream closed for $namespaceId: ${error.message}" }
                emitter.completeWithError(error)
                scope.cancel()
            }
        }
    }

    private fun startHeartbeatJob(
        scope: CoroutineScope,
        emitter: SseEmitter,
        namespaceId: UUID,
    ): Job =
        scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"))
                } catch (error: Exception) {
                    logger.debug { "Namespace heartbeat failed for $namespaceId — client likely disconnected" }
                    scope.cancel()
                }
            }
        }

    companion object : KLogging() {
        const val NAMESPACE_CASE_INVALIDATION_EVENT = "namespace-case-invalidation"
        private val sequence = AtomicLong()
    }
}

package io.whozoss.agentos.a2a

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.agentos.a2a.dto.A2AMessage
import io.whozoss.agentos.a2a.dto.A2APart
import io.whozoss.agentos.a2a.dto.A2ATaskState
import io.whozoss.agentos.a2a.dto.A2ATaskStatus
import io.whozoss.agentos.a2a.dto.AgentCard
import io.whozoss.agentos.a2a.dto.JsonRpcError
import io.whozoss.agentos.a2a.dto.JsonRpcRequest
import io.whozoss.agentos.a2a.dto.JsonRpcResponse
import io.whozoss.agentos.a2a.dto.TaskStatusUpdateEvent
import io.whozoss.agentos.a2a.mapping.CaseEventMapper
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.ResourceNotFoundException
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
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID

/**
 * A2A (Agent2Agent) protocol endpoints — prototype, unauthenticated.
 *
 * URL structure:
 * - `GET  /api/a2a/{namespaceId}/{agentName}/.well-known/agent-card.json` → discovery
 * - `POST /api/a2a/{namespaceId}/{agentName}`                             → JSON-RPC endpoint
 *   - method `message/send`
 *   - method `tasks/get`
 *   - method `tasks/cancel`
 * - `POST /api/a2a/{namespaceId}/{agentName}/stream`                      → SSE for `message/stream`
 *
 * See [docs/a2a.md](../../../../../../../../../docs/a2a.md) for the full picture
 * (mapping, limitations, and what's needed for a production-ready implementation).
 */
@Tag(name = "a2a", description = "A2A protocol endpoints (prototype, no auth)")
@RestController
@RequestMapping("/api/a2a")
class A2AController(
    private val a2aService: A2AService,
    private val jsonRpcHandler: A2AJsonRpcHandler,
    private val caseService: CaseService,
    private val caseEventService: CaseEventService,
) {
    // ---------------------------------------------------------------
    // Agent Card (spec §8)
    // ---------------------------------------------------------------

    @Operation(summary = "Fetch an agent's A2A Agent Card")
    @GetMapping(
        "/{namespaceId}/{agentName}/.well-known/agent-card.json",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun agentCard(
        @PathVariable namespaceId: UUID,
        @PathVariable agentName: String,
        @RequestHeader(value = "Host", required = false) host: String?,
        @RequestHeader(value = "X-Forwarded-Proto", required = false) proto: String?,
    ): AgentCard {
        val config = a2aService.resolveAgent(namespaceId, agentName)
        val scheme = proto ?: "http"
        val baseHost = host ?: "localhost:8124"
        val baseUrl = "$scheme://$baseHost/api/a2a/$namespaceId/$agentName"
        return a2aService.buildAgentCard(config, baseUrl)
    }

    // ---------------------------------------------------------------
    // JSON-RPC endpoint (spec §9)
    // ---------------------------------------------------------------

    @Operation(
        summary = "A2A JSON-RPC endpoint",
        description = "Supported methods: message/send, tasks/get, tasks/cancel. " +
            "For message/stream, POST to /stream instead (SSE).",
    )
    @PostMapping(
        "/{namespaceId}/{agentName}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun jsonRpc(
        @PathVariable namespaceId: UUID,
        @PathVariable agentName: String,
        @RequestBody request: JsonRpcRequest,
    ): JsonRpcResponse {
        val config = a2aService.resolveAgent(namespaceId, agentName)
        return jsonRpcHandler.handle(namespaceId, config, request)
    }

    // ---------------------------------------------------------------
    // Streaming endpoint — message/stream (spec §9.4.2)
    // ---------------------------------------------------------------

    @Tag(name = "sse", description = "SSE endpoint — use EventSource, not a regular HTTP client")
    @Operation(
        summary = "A2A streaming message endpoint (message/stream)",
        description = "SSE stream. Each event `data` is a JsonRpcResponse whose `result` " +
            "is either an A2ATask, a TaskStatusUpdateEvent or a TaskArtifactUpdateEvent.",
    )
    @PostMapping(
        "/{namespaceId}/{agentName}/stream",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = ["text/event-stream"],
    )
    fun stream(
        @PathVariable namespaceId: UUID,
        @PathVariable agentName: String,
        @RequestBody request: JsonRpcRequest,
    ): SseEmitter {
        val config = a2aService.resolveAgent(namespaceId, agentName)

        if (request.method != "message/stream") {
            throw IllegalArgumentException(
                "This endpoint only handles 'message/stream' — got '${request.method}'",
            )
        }
        val params = jsonRpcHandler.parseSendMessageParams(request)

        // Fire the initial send synchronously so we know the taskId before opening the stream.
        val task = a2aService.sendMessage(namespaceId, config, params.message)
        val caseId = UUID.fromString(task.id)

        val emitter = SseEmitter(0L)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Preserve the JSON-RPC id as-is (string, number or null) across all
        // streamed responses per spec §9.4.2.
        val requestId = request.id

        // Send the initial Task snapshot as the first stream event (spec §9.4.2).
        sendEnvelope(emitter, requestId, task)

        startEventPump(
            scope = scope,
            emitter = emitter,
            caseId = caseId,
            taskId = task.id,
            contextId = task.contextId,
            requestId = requestId,
        )
        startHeartbeat(scope, emitter, caseId)

        emitter.onCompletion {
            logger.debug { "A2A SSE completed for case $caseId" }
            scope.cancel()
        }
        emitter.onTimeout {
            logger.debug { "A2A SSE timed out for case $caseId" }
            scope.cancel()
        }
        emitter.onError {
            logger.debug { "A2A SSE error for case $caseId: ${it.message}" }
            scope.cancel()
        }
        return emitter
    }

    // ---------------------------------------------------------------
    // Internals: event pump
    // ---------------------------------------------------------------

    private fun startEventPump(
        scope: CoroutineScope,
        emitter: SseEmitter,
        caseId: UUID,
        taskId: String,
        contextId: String,
        requestId: com.fasterxml.jackson.databind.JsonNode?,
    ): Job = scope.launch {
        try {
            // Replay any events that landed on the case between create and subscribe.
            // In practice this is usually empty (send is synchronous) but we mirror
            // CaseEventSseController's pattern for robustness.
            caseEventService.findByParent(caseId).forEach { ce ->
                CaseEventMapper.toA2AEvents(ce, taskId, contextId).forEach { a2aEvent ->
                    sendEnvelope(emitter, requestId, a2aEvent)
                }
            }

            val runtime = caseService.findActiveRuntime(caseId)
            if (runtime == null) {
                // Case is already terminal — emit a synthetic final status and close.
                val terminalCase = caseService.findById(caseId)
                if (terminalCase != null) {
                    val state = CaseEventMapper.mapStatus(terminalCase.status)
                    sendEnvelope(
                        emitter,
                        requestId,
                        TaskStatusUpdateEvent(
                            taskId = taskId,
                            contextId = contextId,
                            status = A2ATaskStatus(state = state, timestamp = Instant.now().toString()),
                            final = true,
                        ),
                    )
                }
                emitter.complete()
                return@launch
            }

            runtime.events.collect { event ->
                val a2aEvents = CaseEventMapper.toA2AEvents(event, taskId, contextId)
                var shouldClose = false
                a2aEvents.forEach { a2aEvent ->
                    sendEnvelope(emitter, requestId, a2aEvent)
                    if (a2aEvent is TaskStatusUpdateEvent && a2aEvent.final) {
                        shouldClose = true
                    }
                }
                if (shouldClose) {
                    emitter.complete()
                    scope.cancel()
                }
            }
        } catch (e: CancellationException) {
            logger.debug { "A2A SSE pump cancelled for case $caseId" }
            throw e
        } catch (e: Exception) {
            logger.error("A2A SSE pump failed for case $caseId", e)
            runCatching {
                sendEnvelope(
                    emitter,
                    requestId,
                    JsonRpcResponse(
                        id = requestId,
                        error = JsonRpcError(
                            code = JsonRpcError.INTERNAL_ERROR,
                            message = e.message ?: "Stream error",
                        ),
                    ),
                )
            }
            emitter.completeWithError(e)
        }
    }

    private fun startHeartbeat(
        scope: CoroutineScope,
        emitter: SseEmitter,
        caseId: UUID,
    ): Job = scope.launch {
        while (isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"))
            } catch (e: Exception) {
                logger.debug { "A2A SSE heartbeat failed for case $caseId — client gone" }
                scope.cancel()
            }
        }
    }

    /**
     * Wraps [payload] in a JSON-RPC 2.0 response envelope (per spec §9.4.2) and
     * writes it as one SSE event.
     *
     * When [payload] is already a [JsonRpcResponse], it is sent verbatim; otherwise
     * a fresh envelope is built with `result = payload` and the original request id.
     */
    private fun sendEnvelope(
        emitter: SseEmitter,
        requestId: com.fasterxml.jackson.databind.JsonNode?,
        payload: Any,
    ) {
        val envelope = when (payload) {
            is JsonRpcResponse -> payload
            else -> JsonRpcResponse(id = requestId, result = payload)
        }
        val eventName = when (payload) {
            is TaskStatusUpdateEvent -> "status-update"
            is io.whozoss.agentos.a2a.dto.TaskArtifactUpdateEvent -> "artifact-update"
            is io.whozoss.agentos.a2a.dto.A2ATask -> "task"
            else -> "message"
        }
        emitter.send(
            SseEmitter.event()
                .name(eventName)
                .data(envelope, MediaType.APPLICATION_JSON),
        )
    }

    companion object : KLogging() {
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
    }
}

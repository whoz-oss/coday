package io.whozoss.agentos.a2a

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.agentos.a2a.dto.RestArtifact
import io.whozoss.agentos.a2a.dto.RestMessage
import io.whozoss.agentos.a2a.dto.RestPart
import io.whozoss.agentos.a2a.dto.RestRole
import io.whozoss.agentos.a2a.dto.RestSendMessageRequest
import io.whozoss.agentos.a2a.dto.RestStreamResponse
import io.whozoss.agentos.a2a.dto.RestTask
import io.whozoss.agentos.a2a.dto.RestTaskArtifactUpdateEvent
import io.whozoss.agentos.a2a.dto.RestTaskState
import io.whozoss.agentos.a2a.dto.RestTaskStatus
import io.whozoss.agentos.a2a.dto.RestTaskStatusUpdateEvent
import io.whozoss.agentos.a2a.mapping.RestBindingMapper
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID

/**
 * A2A HTTP+JSON/REST binding endpoints (spec §11) — the binding consumed by
 * the [promptfoo A2A provider](https://www.promptfoo.dev/docs/providers/a2a/).
 *
 * These endpoints share [A2AService] with [A2AController] (which handles the
 * JSON-RPC binding), so both bindings drive the same underlying case flow.
 *
 * URL patterns (spec §11.3, appended to the agent's base URL):
 * - `POST /message:send`
 * - `POST /message:stream`  (SSE)
 * - `GET  /tasks/{id}`
 *
 * The colon in `message:send` follows the AIP-136 "custom methods" convention
 * used by the A2A REST binding. Spring MVC's `@PostMapping` accepts colons in
 * path segments without any special escaping.
 */
/**
 * Some A2A HTTP+JSON clients (e.g. promptfoo's `a2a` provider) send
 * `Content-Type: application/a2a+json` instead of plain `application/json`.
 * The wire payload is identical JSON, so both media types are accepted on
 * request bodies below.
 */
private const val A2A_JSON_VALUE = "application/a2a+json"

@Tag(name = "a2a-rest", description = "A2A HTTP+JSON REST binding — used by promptfoo, etc.")
@RestController
@RequestMapping("/api/a2a")
class A2ARestController(
    private val a2aService: A2AService,
    private val caseService: CaseService,
) {
    // -----------------------------------------------------------------
    // message:send
    // -----------------------------------------------------------------

    @Operation(summary = "A2A HTTP+JSON: send a message (non-streaming)")
    @PostMapping(
        "/{namespaceId}/{agentName}/message:send",
        consumes = [MediaType.APPLICATION_JSON_VALUE, A2A_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun sendMessage(
        @PathVariable namespaceId: UUID,
        @PathVariable agentName: String,
        @RequestBody body: RestSendMessageRequest,
    ): RestTask {
        val config = a2aService.resolveAgent(namespaceId, agentName)
        val text = extractText(body.message)
        require(text.isNotBlank()) { "A2A message must contain at least one non-empty text part" }

        val (case, _) = a2aService.getOrCreateCase(
            namespaceId = namespaceId,
            taskId = body.message.taskId,
            seedTitle = text,
        )
        a2aService.sendFollowUp(config, case, text, body.message.messageId)
        val refreshed = a2aService.requireCase(case.id)
        return snapshotWithArtifacts(refreshed)
    }

    // -----------------------------------------------------------------
    // tasks/{id}
    // -----------------------------------------------------------------

    @Operation(summary = "A2A HTTP+JSON: get task snapshot")
    @GetMapping(
        "/{namespaceId}/{agentName}/tasks/{taskId}",
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun getTask(
        @PathVariable namespaceId: UUID,
        @PathVariable agentName: String,
        @PathVariable taskId: UUID,
    ): RestTask {
        // Resolving the agent ensures the URL scope is valid; the task itself
        // is identified by its UUID and is not otherwise agent-scoped.
        a2aService.resolveAgent(namespaceId, agentName)
        val case = a2aService.requireCase(taskId)
        return snapshotWithArtifacts(case)
    }

    // -----------------------------------------------------------------
    // message:stream (SSE)
    // -----------------------------------------------------------------

    @Tag(name = "sse", description = "SSE endpoint — use EventSource, not a regular HTTP client")
    @Operation(summary = "A2A HTTP+JSON: send a message and stream updates (SSE)")
    @PostMapping(
        "/{namespaceId}/{agentName}/message:stream",
        consumes = [MediaType.APPLICATION_JSON_VALUE, A2A_JSON_VALUE],
        produces = ["text/event-stream"],
    )
    fun streamMessage(
        @PathVariable namespaceId: UUID,
        @PathVariable agentName: String,
        @RequestBody body: RestSendMessageRequest,
    ): SseEmitter {
        val config = a2aService.resolveAgent(namespaceId, agentName)
        val text = extractText(body.message)
        require(text.isNotBlank()) { "A2A message must contain at least one non-empty text part" }

        val (case, _) = a2aService.getOrCreateCase(
            namespaceId = namespaceId,
            taskId = body.message.taskId,
            seedTitle = text,
        )
        a2aService.sendFollowUp(config, case, text, body.message.messageId)

        val emitter = SseEmitter(0L)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // First frame: initial Task snapshot (spec §3.1.2 "Task lifecycle stream").
        val initial = snapshotWithArtifacts(a2aService.requireCase(case.id))
        sendFrame(emitter, RestStreamResponse(task = initial))

        startPump(scope, emitter, case.id, initial.id, initial.contextId)
        startHeartbeat(scope, emitter, case.id)

        emitter.onCompletion { scope.cancel() }
        emitter.onTimeout { scope.cancel() }
        emitter.onError { scope.cancel() }
        return emitter
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun startPump(
        scope: CoroutineScope,
        emitter: SseEmitter,
        caseId: UUID,
        taskId: String,
        contextId: String,
    ): Job = scope.launch {
        try {
            val runtime = caseService.findActiveRuntime(caseId)
            if (runtime == null) {
                // Case already terminal — emit a final status and close.
                val terminalCase = caseService.findById(caseId)
                if (terminalCase != null) {
                    val state = RestBindingMapper.mapStatus(terminalCase.status)
                    sendFrame(
                        emitter,
                        RestStreamResponse(
                            statusUpdate = RestTaskStatusUpdateEvent(
                                taskId = taskId,
                                contextId = contextId,
                                status = RestTaskStatus(
                                    state = state,
                                    timestamp = Instant.now().toString(),
                                ),
                                final = true,
                            ),
                        ),
                    )
                }
                emitter.complete()
                return@launch
            }

            runtime.events.collect { event ->
                val payloads = RestBindingMapper.toStreamPayloads(event, taskId, contextId)
                var shouldClose = false
                payloads.forEach { payload ->
                    val envelope = when (payload) {
                        is RestTaskStatusUpdateEvent -> {
                            if (payload.final) shouldClose = true
                            RestStreamResponse(statusUpdate = payload)
                        }
                        is RestTaskArtifactUpdateEvent -> RestStreamResponse(artifactUpdate = payload)
                        is RestMessage -> RestStreamResponse(message = payload)
                        else -> null
                    }
                    if (envelope != null) sendFrame(emitter, envelope)
                }
                if (shouldClose) {
                    emitter.complete()
                    scope.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("A2A REST SSE pump failed for case $caseId", e)
            emitter.completeWithError(e)
        }
    }

    private fun startHeartbeat(
        scope: CoroutineScope,
        emitter: SseEmitter,
        caseId: UUID,
    ): Job = scope.launch {
        while (isActive) {
            delay(HEARTBEAT_MS)
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"))
            } catch (e: Exception) {
                logger.debug { "A2A REST SSE heartbeat failed for case $caseId" }
                scope.cancel()
            }
        }
    }

    private fun sendFrame(emitter: SseEmitter, envelope: RestStreamResponse) {
        emitter.send(
            SseEmitter.event()
                .data(envelope, MediaType.APPLICATION_JSON),
        )
    }

    /**
     * Build a task snapshot with all past agent messages surfaced as artifacts.
     * Promptfoo's default extraction picks the artifact text on `TASK_STATE_COMPLETED`;
     * for us the terminal state is `TASK_STATE_INPUT_REQUIRED` (see docs/a2a.md), so
     * we also fill `status.message` with the last agent message so promptfoo can
     * fall back to it.
     */
    private fun snapshotWithArtifacts(case: Case): RestTask {
        val agentMessages = a2aService.agentMessageEvents(case.id)
        val artifacts: List<RestArtifact>? = agentMessages
            .mapNotNull { RestBindingMapper.messageEventToArtifact(it) }
            .takeIf { it.isNotEmpty() }

        val lastAgentMessage = agentMessages.lastOrNull()
        val statusMessage = lastAgentMessage?.let { msg ->
            RestMessage(
                role = RestRole.ROLE_AGENT,
                parts = msg.content.map { part ->
                    when (part) {
                        is io.whozoss.agentos.sdk.caseEvent.MessageContent.Text ->
                            RestPart(text = part.content)
                        is io.whozoss.agentos.sdk.caseEvent.MessageContent.Image ->
                            RestPart(text = "[image ${part.mimeType}]")
                    }
                },
                messageId = msg.id.toString(),
                taskId = case.id.toString(),
                contextId = case.id.toString(),
            )
        }
        val baseStatus = RestBindingMapper.buildTaskStatus(case).copy(message = statusMessage)
        return RestTask(
            id = case.id.toString(),
            contextId = case.id.toString(),
            status = baseStatus,
            artifacts = artifacts,
            metadata = mapOf(
                "agentos.namespaceId" to case.namespaceId.toString(),
                "agentos.title" to case.title,
                "agentos.status" to case.status.name,
            ),
        )
    }

    private fun extractText(message: RestMessage): String =
        message.parts.joinToString("\n\n") { p ->
            when {
                p.text != null -> p.text
                p.data != null -> p.data.toString()
                p.file != null -> "[file: ${p.file.name ?: p.file.uri ?: "inline"}]"
                else -> ""
            }
        }.trim()

    companion object : KLogging() {
        private const val HEARTBEAT_MS = 15_000L
    }
}

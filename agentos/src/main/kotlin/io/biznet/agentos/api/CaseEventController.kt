package io.biznet.agentos.api

import io.biznet.agentos.orchestration.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * REST API for streaming case events via Server-Sent Events (SSE).
 *
 * Provides real-time event streams for cases, allowing clients to
 * receive updates as agents process requests.
 */
@RestController
@RequestMapping("/api/cases")
class CaseEventController(
    private val caseService: CaseService,
) {
    private val logger = LoggerFactory.getLogger(CaseEventController::class.java)

    /**
     * Stream events for a case via SSE.
     *
     * GET /api/cases/:caseId/events
     *
     * Returns a Server-Sent Events stream that emits all events
     * generated during case execution.
     */
    @GetMapping("/{caseId}/events")
    fun streamEvents(
        @PathVariable caseId: UUID,
    ): SseEmitter {
        logger.info("Client connecting to event stream for case: $caseId")

        val emitter = SseEmitter(0L) // Infinite timeout

        val case = caseService.getCaseInstance(caseId)
        if (case == null) {
            logger.warn("Case not found: $caseId")
            emitter.completeWithError(IllegalArgumentException("Case $caseId not found"))
            return emitter
        }

        // Create a coroutine scope for this SSE connection
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Collect events from the SharedFlow and send via SSE
        val collectorJob =
            scope.launch {
                try {
                    case.events.collect { event ->
                        try {
                            val sseEvent =
                                SseEmitter
                                    .event()
                                    .id(event.id.toString())
                                    .name(event.type.value)
                                    .data(event.toEventData())

                            emitter.send(sseEvent)
                            logger.trace("Event ${event.type} sent to SSE for case $caseId")
                        } catch (e: Exception) {
                            logger.debug("Failed to send event to SSE for case $caseId: ${e.message}")
                            throw e // Stop collecting
                        }
                    }
                } catch (error: Exception) {
                    logger.error("Error in event stream for case $caseId", error)
                    emitter.completeWithError(error)
                }
            }

        // Setup cleanup handlers
        emitter.onCompletion {
            logger.debug("SSE emitter completed for case $caseId")
            collectorJob.cancel()
            scope.cancel()
        }

        emitter.onTimeout {
            logger.debug("SSE emitter timed out for case $caseId")
            collectorJob.cancel()
            scope.cancel()
        }

        emitter.onError { throwable ->
            logger.warn("SSE emitter error for case $caseId: ${throwable.message}")
            collectorJob.cancel()
            scope.cancel()
        }

        logger.info("SSE connection established for case: $caseId")
        return emitter
    }
}

// ========================================
// Event Data DTOs
// ========================================

/**
 * Convert CaseEvent to a simplified DTO for SSE transmission.
 */
private fun CaseEvent.toEventData(): Any =
    when (this) {
        is MessageEvent ->
            MessageEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                actor = actor,
                content = content,
            )

        is ToolRequestEvent ->
            ToolRequestEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                toolRequestId = toolRequestId,
                toolName = toolName,
                args = args,
            )

        is ToolResponseEvent ->
            ToolResponseEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                toolRequestId = toolRequestId,
                toolName = toolName,
                output = output,
                success = success,
            )

        is ThinkingEvent ->
            ThinkingEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
            )

        is AgentSelectedEvent ->
            AgentSelectedEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                agentId = agentId,
                agentName = agentName,
            )

        is AgentRunningEvent ->
            AgentRunningEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                agentId = agentId,
                agentName = agentName,
            )

        is AgentFinishedEvent ->
            AgentFinishedEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                agentId = agentId,
                agentName = agentName,
            )

        is CaseStatusEvent ->
            CaseStatusEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                status = status,
            )

        is WarnEvent ->
            WarnEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                message = message,
            )

        is QuestionEvent ->
            QuestionEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                agentId = agentId,
                agentName = agentName,
                question = question,
                options = options,
            )

        is AnswerEvent ->
            AnswerEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                questionId = questionId,
                actor = actor,
                answer = answer,
            )

        is IntentionGeneratedEvent ->
            IntentionGeneratedEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                agentId = agentId,
                intention = intention,
            )

        is ToolSelectedEvent ->
            ToolSelectedEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                agentId = agentId,
                toolName = toolName,
            )

        is TextChunkEvent ->
            TextChunkEventData(
                id = id,
                caseId = caseId,
                projectId = projectId,
                timestamp = timestamp.toString(),
                chunk = chunk,
            )
    }

// Event data classes for SSE transmission
data class MessageEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val actor: Actor,
    val content: List<MessageContent>,
)

data class ToolRequestEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val toolRequestId: String,
    val toolName: String,
    val args: String,
)

data class ToolResponseEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val toolRequestId: String,
    val toolName: String,
    val output: MessageContent,
    val success: Boolean,
)

data class ThinkingEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
)

data class AgentSelectedEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val agentId: UUID,
    val agentName: String,
)

data class AgentRunningEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val agentId: UUID,
    val agentName: String,
)

data class AgentFinishedEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val agentId: UUID,
    val agentName: String,
)

data class CaseStatusEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val status: CaseStatus,
)

data class WarnEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val message: String,
)

data class QuestionEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val agentId: UUID,
    val agentName: String,
    val question: String,
    val options: List<String>?,
)

data class AnswerEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val questionId: UUID,
    val actor: Actor,
    val answer: String,
)

data class IntentionGeneratedEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val agentId: UUID,
    val intention: String,
)

data class ToolSelectedEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val agentId: UUID,
    val toolName: String,
)

data class TextChunkEventData(
    val id: UUID,
    val caseId: UUID,
    val projectId: UUID,
    val timestamp: String,
    val chunk: String,
)

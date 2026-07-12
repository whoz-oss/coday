package io.whozoss.agentos.a2a.mapping

import io.whozoss.agentos.a2a.dto.RestArtifact
import io.whozoss.agentos.a2a.dto.RestMessage
import io.whozoss.agentos.a2a.dto.RestPart
import io.whozoss.agentos.a2a.dto.RestRole
import io.whozoss.agentos.a2a.dto.RestTask
import io.whozoss.agentos.a2a.dto.RestTaskArtifactUpdateEvent
import io.whozoss.agentos.a2a.dto.RestTaskState
import io.whozoss.agentos.a2a.dto.RestTaskStatus
import io.whozoss.agentos.a2a.dto.RestTaskStatusUpdateEvent
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus

/**
 * Maps AgentOS domain to the A2A HTTP+JSON/REST binding wire format
 * (proto-style constants: `ROLE_USER`, `TASK_STATE_COMPLETED`, …).
 *
 * The JSON-RPC binding uses different lowercase strings and is handled by
 * [CaseEventMapper] — the two mappers intentionally live side by side rather
 * than sharing a single "canonical" model so that each binding stays close
 * to what its clients expect.
 */
object RestBindingMapper {
    /**
     * Map [CaseStatus] to a REST-binding [RestTaskState].
     *
     * Prototype heuristic — IDLE is treated as `TASK_STATE_COMPLETED` rather
     * than `TASK_STATE_INPUT_REQUIRED` even though semantically both are
     * valid AgentOS "turn ended" outcomes. Reason: promptfoo (and most A2A
     * eval clients) expect a terminal state to close the polling loop and
     * extract artifacts; `TASK_STATE_INPUT_REQUIRED` would be surfaced as
     * "task requires additional input" and evaluated as an error.
     *
     * The tradeoff: the client cannot tell "agent is done" from "agent is
     * waiting for the user". A proper implementation would introduce an
     * explicit AgentDoneEvent to disambiguate — see docs/a2a.md §5.2.
     */
    fun mapStatus(status: CaseStatus): RestTaskState =
        when (status) {
            CaseStatus.PENDING -> RestTaskState.TASK_STATE_SUBMITTED
            CaseStatus.RUNNING -> RestTaskState.TASK_STATE_WORKING
            CaseStatus.IDLE -> RestTaskState.TASK_STATE_COMPLETED
            CaseStatus.KILLED -> RestTaskState.TASK_STATE_CANCELED
            CaseStatus.ERROR -> RestTaskState.TASK_STATE_FAILED
        }

    fun buildTaskStatus(case: Case): RestTaskStatus =
        RestTaskStatus(
            state = mapStatus(case.status),
            timestamp = case.metadata.modified.toString(),
        )

    fun buildTaskSnapshot(case: Case, artifacts: List<RestArtifact>? = null): RestTask =
        RestTask(
            id = case.id.toString(),
            contextId = case.id.toString(),
            status = buildTaskStatus(case),
            history = null,
            artifacts = artifacts,
            metadata = mapOf(
                "agentos.namespaceId" to case.namespaceId.toString(),
                "agentos.title" to case.title,
            ),
        )

    /**
     * Map a persisted [MessageEvent] with agent role to a REST artifact.
     * Returns null for user/system messages (not surfaced as agent output).
     */
    fun messageEventToArtifact(event: MessageEvent): RestArtifact? {
        if (event.actor.role != ActorRole.AGENT) return null
        return RestArtifact(
            artifactId = event.id.toString(),
            name = "response",
            parts = event.content.map { it.toRestPart() },
        )
    }

    /**
     * Convert a live [CaseEvent] into zero or more streaming payloads for
     * the `message:stream` endpoint. Only status/message/error events are
     * exposed; tool activity remains internal (spec §1.2 "Opaque Execution").
     *
     * Return value is a list of one of [RestTaskStatusUpdateEvent],
     * [RestTaskArtifactUpdateEvent], or [RestMessage] instances — mirroring
     * the proto `StreamResponse` oneof.
     */
    fun toStreamPayloads(
        event: CaseEvent,
        taskId: String,
        contextId: String,
    ): List<Any> =
        when (event) {
            is CaseStatusEvent -> {
                val state = mapStatus(event.status)
                listOf(
                    RestTaskStatusUpdateEvent(
                        taskId = taskId,
                        contextId = contextId,
                        status = RestTaskStatus(state = state, timestamp = event.timestamp.toString()),
                        final = state.isTerminal() || state == RestTaskState.TASK_STATE_INPUT_REQUIRED,
                    ),
                )
            }
            is AgentRunningEvent -> listOf(
                RestTaskStatusUpdateEvent(
                    taskId = taskId,
                    contextId = contextId,
                    status = RestTaskStatus(
                        state = RestTaskState.TASK_STATE_WORKING,
                        timestamp = event.timestamp.toString(),
                    ),
                    final = false,
                ),
            )
            is MessageEvent -> {
                messageEventToArtifact(event)?.let { artifact ->
                    listOf(
                        RestTaskArtifactUpdateEvent(
                            taskId = taskId,
                            contextId = contextId,
                            artifact = artifact,
                            append = false,
                            lastChunk = true,
                        ),
                    )
                } ?: emptyList()
            }
            is QuestionEvent -> listOf(
                RestTaskStatusUpdateEvent(
                    taskId = taskId,
                    contextId = contextId,
                    status = RestTaskStatus(
                        state = RestTaskState.TASK_STATE_INPUT_REQUIRED,
                        timestamp = event.timestamp.toString(),
                        message = RestMessage(
                            role = RestRole.ROLE_AGENT,
                            parts = listOf(RestPart(text = event.question)),
                            messageId = event.id.toString(),
                            taskId = taskId,
                            contextId = contextId,
                        ),
                    ),
                    final = true,
                ),
            )
            is ErrorEvent -> listOf(
                RestTaskStatusUpdateEvent(
                    taskId = taskId,
                    contextId = contextId,
                    status = RestTaskStatus(
                        state = RestTaskState.TASK_STATE_FAILED,
                        timestamp = event.timestamp.toString(),
                        message = RestMessage(
                            role = RestRole.ROLE_AGENT,
                            parts = listOf(RestPart(text = event.message)),
                            messageId = event.id.toString(),
                            taskId = taskId,
                            contextId = contextId,
                        ),
                    ),
                    final = true,
                ),
            )
            else -> emptyList()
        }

    private fun MessageContent.toRestPart(): RestPart =
        when (this) {
            is MessageContent.Text -> RestPart(text = content)
            is MessageContent.Image -> RestPart(
                text = "[image ${mimeType}, ${content.length} b64 chars]",
                metadata = mapOf("mimeType" to mimeType, "width" to width, "height" to height),
            )
        }
}

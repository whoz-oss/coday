package io.whozoss.agentos.workflow

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseFlow.SessionContextKeys

/**
 * The contract of the `agentLoop` entry of a message's session context: the single place that knows how
 * an [AgentLoopPayload] is carried there, used by the writer (the scheduler, in loop mode) and by the
 * reader ([io.whozoss.agentos.agent.AgentLoop]).
 *
 * The payload travels as plain maps and lists, so that it can be persisted on a
 * [io.whozoss.agentos.sdk.caseEvent.MessageEvent] like any other session context value.
 */
object AgentLoopSessionContext {
    private val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}

    /** The session context entry carrying [payload], as plain maps/lists (safe to persist on a MessageEvent). */
    fun of(
        payload: AgentLoopPayload,
        objectMapper: ObjectMapper,
    ): Map<String, Any?> =
        mapOf(SessionContextKeys.AGENT_LOOP to objectMapper.convertValue(payload, MAP_TYPE))

    /**
     * The payload carried by [sessionContext], null when absent.
     *
     * @throws IllegalArgumentException when the entry is present but cannot be parsed as an [AgentLoopPayload].
     */
    fun read(
        sessionContext: Map<String, Any?>?,
        objectMapper: ObjectMapper,
    ): AgentLoopPayload? {
        val raw = sessionContext?.get(SessionContextKeys.AGENT_LOOP) ?: return null
        return objectMapper.convertValue(raw, AgentLoopPayload::class.java)
    }
}

package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseFlow.SessionContextKeys

/**
 * The contract of the `loopData` entry of a message's session context: the single place that knows how the
 * initial data of an agent loop run is carried there, used by the writer (the scheduler, in loop mode) and by
 * the reader ([io.whozoss.agentos.agent.AgentLoop]).
 *
 * `loopData` travels as plain maps and lists, so that it can be persisted on a
 * [io.whozoss.agentos.sdk.caseEvent.MessageEvent] like any other session context value.
 */
object LoopDataSessionContext {
    /** The session context carrying [loopData] under [SessionContextKeys.LOOP_DATA]. */
    fun of(loopData: Map<String, Any?>): Map<String, Any?> = mapOf(SessionContextKeys.LOOP_DATA to loopData)

    /**
     * The `loopData` carried by [sessionContext], null when absent.
     *
     * @throws IllegalArgumentException when the entry is present but is not a JSON object.
     */
    fun read(
        sessionContext: Map<String, Any?>?,
        objectMapper: ObjectMapper,
    ): JsonNode? {
        val raw = sessionContext?.get(SessionContextKeys.LOOP_DATA) ?: return null
        val node = objectMapper.valueToTree<JsonNode>(raw)
        require(node.isObject) { "The '${SessionContextKeys.LOOP_DATA}' session context entry must be an object, got ${node.nodeType}" }
        return node
    }
}

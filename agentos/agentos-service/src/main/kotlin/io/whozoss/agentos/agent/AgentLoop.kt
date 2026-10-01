package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KLogging
import java.util.UUID

/**
 * A zero-LLM agent that executes a hardcoded pipeline over a set of entities.
 *
 * Unlike [AgentSimple] or [AgentAdvanced], [AgentLoop] never calls an AI provider.
 * It is designed to be triggered programmatically (e.g. by a scheduler or a UI form)
 * to process entities in bulk using a predefined SEARCH → EXTRACT → ACT sequence.
 *
 * ## MVP scope
 *
 * The current implementation is a skeleton:
 * - Parses the initial [MessageEvent] payload as JSON
 * - Emits [ThinkingEvent] to signal processing to the UI
 * - Logs the parsed payload
 * - Emits [AgentFinishedEvent] to close the run
 *
 * The full SEARCH → EXTRACT → ACT pipeline will be implemented in subsequent tickets
 * once the Search* structured-output contract and CreateCase tool are available.
 *
 * ## Expected payload format
 *
 * The first user [MessageEvent] must carry a JSON text content:
 * ```json
 * {
 *   "entityType": "TALENT",
 *   "filters": { ... },
 *   "searchOptions": { "limit": 25 },
 *   "act": {
 *     "agentName": "talent-analyzer",
 *     "promptTemplate": "Analyse ce talent : {entityId}"
 *   }
 * }
 * ```
 *
 * If the payload cannot be parsed, the agent logs a warning and finishes immediately
 * without error — this is intentional: a misconfigured trigger should not crash the case.
 *
 * ## Interruption
 *
 * [shouldContinue] is polled before processing starts. When it returns false
 * (e.g. a kill signal was received), the agent emits [AgentFinishedEvent] immediately.
 *
 * @param metadata Agent identity, inherited from [Entity].
 * @param name Display name of this agent instance.
 * @param objectMapper Used to parse the JSON payload from the initial message.
 */
class AgentLoop(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val objectMapper: ObjectMapper,
) : Agent {
    /**
     * AgentLoop does not call any LLM — these fields satisfy the [Agent] contract
     * but carry no runtime meaning for this implementation.
     */
    override val llmProvider: String = PROVIDER_NAME
    override val llmModel: String = MODEL_NAME

    override fun run(
        events: List<CaseEvent>,
        shouldContinue: () -> Boolean,
    ): Flow<CaseEvent> =
        flow {
            val namespaceId = events.firstOrNull()?.namespaceId
                ?: throw IllegalArgumentException("[AgentLoop] No events provided — cannot resolve namespaceId")
            val caseId = events.firstOrNull()?.caseId
                ?: throw IllegalArgumentException("[AgentLoop] No events provided — cannot resolve caseId")

            // Honour kill/interrupt signal before doing any work.
            if (!shouldContinue()) {
                logger.info { "[AgentLoop] '$name' interrupted before start (caseId=$caseId)" }
                emit(finishedEvent(namespaceId, caseId))
                return@flow
            }

            emit(ThinkingEvent(namespaceId = namespaceId, caseId = caseId))

            // Parse the payload from the first user message.
            val payload = parsePayload(events)
            if (payload == null) {
                logger.warn {
                    "[AgentLoop] '$name' could not parse a valid AgentLoopPayload from the initial message " +
                        "(caseId=$caseId). Finishing without processing."
                }
                emit(finishedEvent(namespaceId, caseId))
                return@flow
            }

            logger.info {
                "[AgentLoop] '$name' starting — entityType=${payload.entityType}, " +
                    "actAgent=${payload.act?.agentName} (caseId=$caseId)"
            }

            // TODO: implement SEARCH → EXTRACT → ACT pipeline
            // Blocked on:
            //   1. Search* structured-output contract (peer ticket)
            //   2. CreateCase tool implementation
            //
            // For now, the loop finishes immediately after logging the payload.
            // This skeleton validates the ExecutionMode.LOOP wiring end-to-end.

            emit(finishedEvent(namespaceId, caseId))
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun finishedEvent(
        namespaceId: UUID,
        caseId: UUID,
    ) = AgentFinishedEvent(
        namespaceId = namespaceId,
        caseId = caseId,
        agentId = id,
        agentName = name,
        // No LLM was used — provider/model are omitted.
        llmProvider = null,
        llmModel = null,
    )

    /**
     * Extracts and parses the JSON payload from the first user [MessageEvent].
     *
     * Returns null when:
     * - No [MessageEvent] with USER role is found in [events]
     * - The text content cannot be parsed as [AgentLoopPayload]
     * - The payload is missing the required [AgentLoopPayload.entityType] field
     */
    private fun parsePayload(events: List<CaseEvent>): AgentLoopPayload? {
        val firstUserMessage = events
            .filterIsInstance<MessageEvent>()
            .firstOrNull { it.actor.role == ActorRole.USER }
            ?: run {
                logger.warn { "[AgentLoop] No user MessageEvent found in event history" }
                return null
            }

        val text = firstUserMessage.content
            .filterIsInstance<MessageContent.Text>()
            .joinToString("\n") { it.content }
            .takeIf { it.isNotBlank() }
            ?: run {
                logger.warn { "[AgentLoop] First user message has no text content" }
                return null
            }

        return runCatching {
            objectMapper.readValue(text, AgentLoopPayload::class.java)
        }.getOrElse { e ->
            logger.warn(e) { "[AgentLoop] Failed to parse payload JSON: $text" }
            null
        }?.takeIf { it.entityType.isNotBlank() }
            ?: run {
                logger.warn { "[AgentLoop] Payload is missing required 'entityType' field" }
                null
            }
    }

    companion object : KLogging() {
        /**
         * Sentinel values satisfying the [Agent.llmProvider] and [Agent.llmModel] contract.
         * AgentLoop never calls an LLM — these values identify the implementation, not a provider.
         */
        const val PROVIDER_NAME = "none"
        const val MODEL_NAME = "agent-loop"
    }
}

/**
 * Parsed representation of the JSON payload expected in the first user message of an AgentLoop case.
 *
 * All fields except [entityType] are optional — the pipeline will use sensible defaults
 * when they are absent.
 *
 * @param entityType The type of entities to process (e.g. "TALENT", "TASK", "DOSSIER").
 * @param filters Opaque filter map passed verbatim to the Search* tool.
 * @param searchOptions Pagination and limit options for the search phase.
 * @param act Configuration for the action to execute per entity.
 */
data class AgentLoopPayload(
    val entityType: String,
    val filters: Map<String, Any?>? = null,
    val searchOptions: AgentLoopSearchOptions? = null,
    val act: AgentLoopActConfig? = null,
)

/**
 * Pagination options for the SEARCH phase.
 *
 * @param limit Maximum number of entities per page (default: 25).
 */
data class AgentLoopSearchOptions(
    val limit: Int = 25,
)

/**
 * Configuration for the ACT phase: one sub-case per entity.
 *
 * @param agentName Name of the agent to invoke for each entity.
 * @param promptTemplate Template for the initial message sent to the agent.
 *   Use `{entityId}` as a placeholder for the entity identifier.
 */
data class AgentLoopActConfig(
    val agentName: String,
    val promptTemplate: String,
)

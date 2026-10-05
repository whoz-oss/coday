package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.agent.Agent
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.user.UserService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KLogging
import java.util.UUID

/**
 * A zero-LLM agent that executes a hardcoded SEARCH → ACT pipeline over a set of entities.
 *
 * Unlike [AgentSimple] or [AgentAdvanced], [AgentLoop] never calls an AI provider.
 * It is designed to be triggered programmatically (e.g. by a scheduler or a UI form)
 * to process entities in bulk by calling a Search tool and then (eventually) launching
 * one case per returned entity.
 *
 * ## Flow
 *
 * 1. Parse the JSON payload from the first user [MessageEvent].
 * 2. Find the Search tool by name in [resolvedTools].
 * 3. Call the tool with [AgentLoopPayload.searchInput] (first page only for MVP).
 * 4. Parse the structured result as [SearchResult].
 * 5. For each item in [SearchResult.data]:
 *    a. Resolve the end-user via [UserService.findByExternalId] — skip if null.
 *    b. Expand [AgentLoopAct.promptTemplate] with the entity id.
 *    c. Log the intended action (case creation not yet implemented — see TODO below).
 * 6. Emit a summary [MessageEvent] and [AgentFinishedEvent].
 *
 * ## Expected payload format
 *
 * The first user [MessageEvent] must carry a JSON text content:
 * ```json
 * {
 *   "tool": "SearchTalents",
 *   "searchInput": { "endDatePeriod": ["THIS_WEEK"], "resolveTargets": ["OWNER"] },
 *   "act": {
 *     "agentName": "talent-analyzer",
 *     "promptTemplate": "Analyse this entity: {entityId}"
 *   }
 * }
 * ```
 *
 * ## Search tool output format
 *
 * The tool must return a [ToolExecutionResult][io.whozoss.agentos.sdk.tool.ToolExecutionResult]
 * whose [structuredOutput][io.whozoss.agentos.sdk.tool.ToolExecutionResult.structuredOutput]
 * conforms to:
 * ```json
 * {
 *   "data": [{"entityType": "TALENT", "entityId": "ext-user-123"}],
 *   "metadata": {"totalCount": 42, "next": null}
 * }
 * ```
 *
 * ## Interruption
 *
 * [shouldContinue] is polled before processing starts. When it returns false
 * (e.g. a kill signal was received), the agent emits [AgentFinishedEvent] immediately.
 *
 * @param metadata          Agent identity, inherited from [Entity].
 * @param name              Display name of this agent instance.
 * @param objectMapper      Used to parse the JSON payload and search results.
 * @param resolvedTools     Full set of tools available to this agent — used to look up
 *                          the Search tool by name.
 * @param userService       Used to resolve end-users from their external id.
 * @param triggerUserId     Internal UUID of the user who triggered the AgentLoop run.
 *                          Used to build the [ToolContext] for the Search tool call.
 */
class AgentLoop(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val name: String,
    private val objectMapper: ObjectMapper,
    private val resolvedTools: Collection<StandardTool<*>> = emptyList(),
    private val userService: UserService? = null,
    private val triggerUserId: UUID? = null,
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
            val namespaceId =
                events.firstOrNull()?.namespaceId
                    ?: throw IllegalArgumentException("[AgentLoop] No events provided — cannot resolve namespaceId")
            val caseId =
                events.firstOrNull()?.caseId
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
                "[AgentLoop] '$name' starting — tool=${payload.tool}, " +
                    "actAgent=${payload.act.agentName} (caseId=$caseId)"
            }

            // Find the Search tool by name.
            val searchTool = resolvedTools.find { it.name == payload.tool }
            if (searchTool == null) {
                logger.error {
                    "[AgentLoop] '$name' could not find Search tool '${payload.tool}' in resolved tools " +
                        "(available: ${resolvedTools.map { it.name }}). Finishing."
                }
                emit(finishedEvent(namespaceId, caseId))
                return@flow
            }

            // Build a ToolContext scoped to the trigger user.
            val toolContext =
                ToolContext(
                    namespaceId = namespaceId,
                    userId = triggerUserId,
                    userExternalId = null,
                    caseEvents = events,
                    agentName = name,
                )

            // SEARCH phase: call the tool with the opaque searchInput.
            val searchJson = objectMapper.writeValueAsString(payload.searchInput)
            val searchResult =
                runCatching { searchTool.executeWithJson(searchJson, toolContext) }
                    .getOrElse { e ->
                        logger.error(e) { "[AgentLoop] '$name' Search tool '${payload.tool}' threw an exception (caseId=$caseId)" }
                        emit(finishedEvent(namespaceId, caseId))
                        return@flow
                    }

            if (!searchResult.success) {
                logger.error {
                    "[AgentLoop] '$name' Search tool '${payload.tool}' returned failure: ${searchResult.output} (caseId=$caseId)"
                }
                emit(finishedEvent(namespaceId, caseId))
                return@flow
            }

            // Parse the structured output from the Search tool.
            val structured = searchResult.structuredOutput
            if (structured == null) {
                logger.error {
                    "[AgentLoop] '$name' Search tool '${payload.tool}' returned no structuredOutput. " +
                        "Check the tool's outputSchema() (caseId=$caseId)"
                }
                emit(finishedEvent(namespaceId, caseId))
                return@flow
            }

            val parsedSearch =
                runCatching { objectMapper.treeToValue(structured, SearchResult::class.java) }
                    .getOrElse { e ->
                        logger.error(e) { "[AgentLoop] '$name' Failed to parse SearchResult from structuredOutput (caseId=$caseId)" }
                        emit(finishedEvent(namespaceId, caseId))
                        return@flow
                    }

            logger.info {
                "[AgentLoop] '$name' search returned ${parsedSearch.data.size} item(s) " +
                    "(totalCount=${parsedSearch.metadata?.totalCount}, caseId=$caseId)"
            }

            // ACT phase: log intended action per entity — case creation not yet implemented.
            var launchedCount = 0
            var skippedCount = 0

            for (item in parsedSearch.data) {
                if (!shouldContinue()) {
                    logger.info { "[AgentLoop] '$name' interrupted during ACT phase after $launchedCount item(s) processed (caseId=$caseId)" }
                    break
                }

                // Resolve the end-user from the entity external id.
                val endUser = userService?.findByExternalId(item.entityId)
                if (endUser == null) {
                    logger.warn { "[AgentLoop] No user found for entityId='${item.entityId}' — skipping" }
                    skippedCount++
                    continue
                }

                // Expand the prompt template.
                val task = payload.act.promptTemplate.replace("{entityId}", item.entityId)

                logger.info {
                    "[AgentLoop] Would create case for entity '${item.entityId}' " +
                        "(user=${endUser.metadata.id}), agent=${payload.act.agentName}, task=$task"
                }
                // TODO: create a standard Case on behalf of endUser via a dedicated tool or service
                launchedCount++
            }

            // Emit a summary message for traceability.
            val summary =
                "AgentLoop '$name' completed: launched $launchedCount case(s), " +
                    "skipped $skippedCount out of ${parsedSearch.data.size} entity/entities " +
                    "(totalCount=${parsedSearch.metadata?.totalCount})."
            logger.info { "[AgentLoop] $summary (caseId=$caseId)" }
            emit(
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor =
                        io.whozoss.agentos.sdk.actor.Actor(
                            id = id.toString(),
                            displayName = name,
                            role = io.whozoss.agentos.sdk.actor.ActorRole.AGENT,
                        ),
                    content = listOf(MessageContent.Text(summary)),
                ),
            )

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
     * - The payload has a blank [AgentLoopPayload.tool] field
     */
    private fun parsePayload(events: List<CaseEvent>): AgentLoopPayload? {
        val firstUserMessage =
            events
                .filterIsInstance<MessageEvent>()
                .firstOrNull { it.actor.role == ActorRole.USER }
                ?: run {
                    logger.warn { "[AgentLoop] No user MessageEvent found in event history" }
                    return null
                }

        val text =
            firstUserMessage.content
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
        }?.takeIf { it.tool.isNotBlank() }
            ?: run {
                logger.warn { "[AgentLoop] Payload is missing required 'tool' field" }
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
 * @param tool        Name of the Search tool to invoke (e.g. "SearchTalents").
 * @param searchInput Opaque JSON block passed verbatim to the Search tool.
 * @param act         Configuration for the action to execute per entity.
 */
data class AgentLoopPayload(
    val tool: String,
    val searchInput: JsonNode,
    val act: AgentLoopAct,
)

/**
 * Configuration for the ACT phase: one case per entity.
 *
 * @param agentName      Name of the agent to invoke for each entity.
 * @param promptTemplate Template for the initial message sent to the agent.
 *   Use `{entityId}` as a placeholder for the entity external identifier.
 */
data class AgentLoopAct(
    val agentName: String,
    val promptTemplate: String,
)

/**
 * A single entity returned by the Search tool.
 *
 * @param entityType Category of the entity (e.g. "TALENT", "TASK").
 * @param entityId   External identifier used to resolve the end-user via
 *                   [io.whozoss.agentos.user.UserService.findByExternalId].
 */
data class SearchResultItem(
    val entityType: String,
    val entityId: String,
)

/**
 * Pagination metadata returned alongside [SearchResult.data].
 *
 * @param totalCount Total number of matching entities across all pages.
 * @param next       Cursor for the next page, or null when this is the last page.
 */
data class SearchResultMetadata(
    val totalCount: Int?,
    val next: String?,
)

/**
 * Structured output of a Search tool call.
 *
 * @param data     List of entities on the current page.
 * @param metadata Pagination metadata.
 */
data class SearchResult(
    val data: List<SearchResultItem>,
    val metadata: SearchResultMetadata?,
)

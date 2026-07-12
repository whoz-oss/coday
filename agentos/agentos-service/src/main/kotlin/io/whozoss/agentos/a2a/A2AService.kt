package io.whozoss.agentos.a2a

import io.whozoss.agentos.a2a.dto.A2AMessage
import io.whozoss.agentos.a2a.dto.A2APart
import io.whozoss.agentos.a2a.dto.A2ATask
import io.whozoss.agentos.a2a.dto.A2ATaskState
import io.whozoss.agentos.a2a.dto.AgentCapabilities
import io.whozoss.agentos.a2a.dto.AgentCard
import io.whozoss.agentos.a2a.dto.AgentInterface
import io.whozoss.agentos.a2a.dto.AgentSkill
import io.whozoss.agentos.a2a.mapping.CaseEventMapper
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Orchestrates A2A operations by mapping them onto AgentOS primitives:
 * - A2A `Agent` (URL-addressable) → [AgentConfig] resolved by (namespaceId, agentName)
 * - A2A `Task`                     → AgentOS [Case]
 * - A2A `Message` (user)           → user [MessageContent] injected via [CaseService.addMessage]
 * - Agent targeting                → `@AgentName` prefix injected in the message text,
 *                                    picked up by [io.whozoss.agentos.caseFlow.CaseServiceImpl.selectAgent]
 *
 * Prototype: no authentication. All exposed agents are those with `enabled = true`
 * in their namespace. See docs/a2a.md for the full limitations list.
 */
@Service
class A2AService(
    private val agentConfigService: AgentConfigService,
    private val caseService: CaseService,
    private val namespaceService: NamespaceService,
    private val caseEventService: CaseEventService,
) {
    /**
     * Resolve an [AgentConfig] published in [namespaceId] under [agentName].
     *
     * Throws [ResourceNotFoundException] when the namespace does not exist, when
     * no matching agent is found, or when the agent is not enabled.
     */
    fun resolveAgent(namespaceId: UUID, agentName: String): AgentConfig {
        namespaceService.findById(namespaceId)
            ?: throw ResourceNotFoundException("Namespace $namespaceId not found")

        val config = agentConfigService.findByName(namespaceId, agentName)
            ?: throw ResourceNotFoundException("Agent '$agentName' not found in namespace $namespaceId")

        if (!config.enabled) {
            // Prototype policy: only `enabled = true` agents are exposed via A2A.
            throw ResourceNotFoundException("Agent '$agentName' is not enabled for A2A exposure")
        }
        return config
    }

    /**
     * List agents exposed for A2A in [namespaceId] (i.e. `enabled = true`).
     */
    fun listExposedAgents(namespaceId: UUID): List<AgentConfig> {
        namespaceService.findById(namespaceId)
            ?: throw ResourceNotFoundException("Namespace $namespaceId not found")
        return agentConfigService.findByNamespace(namespaceId, withDisabled = false)
    }

    /**
     * Build the [AgentCard] served at
     * `/api/a2a/{namespaceId}/{agentName}/.well-known/agent-card.json`.
     *
     * The agent is exposed via two transports (spec §5.2):
     * - JSON-RPC (preferred) at `{baseUrl}`
     * - HTTP+JSON at `{baseUrl}` — REST endpoints appended as `/message:send`, etc.
     *
     * [baseUrl] is the absolute URL of the agent's endpoints (no trailing slash),
     * e.g. `http://localhost:8124/api/a2a/{namespaceId}/{agentName}`.
     */
    fun buildAgentCard(config: AgentConfig, baseUrl: String): AgentCard =
        AgentCard(
            name = config.name,
            description = config.description ?: "AgentOS agent '${config.name}'",
            url = baseUrl,
            preferredTransport = "JSONRPC",
            additionalInterfaces = listOf(
                AgentInterface(url = baseUrl, transport = "JSONRPC"),
                AgentInterface(url = baseUrl, transport = "HTTP+JSON"),
            ),
            capabilities = AgentCapabilities(
                streaming = true,
                pushNotifications = false,
                stateTransitionHistory = false,
            ),
            skills = listOf(
                AgentSkill(
                    id = "default",
                    name = config.name,
                    description = config.description ?: "Default skill",
                    tags = emptyList(),
                ),
            ),
        )

    /**
     * Handle an A2A `message/send` (or the initial step of `message/stream`).
     *
     * Behavior:
     * - If `message.taskId` is null, create a new [Case] in [namespaceId].
     * - Extract the text parts of the incoming [A2AMessage], prefix them with
     *   `@AgentName` so [io.whozoss.agentos.caseFlow.CaseServiceImpl.selectAgent]
     *   binds the case to [config], then post as a USER [MessageContent.Text].
     * - Return the resulting [A2ATask] snapshot immediately (task run continues
     *   asynchronously in the case runtime).
     *
     * @throws ResourceNotFoundException when [SendMessageParams.message.taskId] is set
     *   but references an unknown case.
     */
    fun sendMessage(
        namespaceId: UUID,
        config: AgentConfig,
        message: A2AMessage,
    ): A2ATask {
        val text = extractText(message)
        require(text.isNotBlank()) { "A2A message must contain at least one non-empty text part" }

        val case: Case = when (val existingId = message.taskId?.let(::parseUuidOrNull)) {
            null -> createCase(namespaceId, text)
            else -> caseService.findById(existingId)
                ?: throw ResourceNotFoundException("Task $existingId (case) not found")
        }

        // Force agent selection via @mention. This piggy-backs on the existing
        // CaseServiceImpl.selectAgent path — no core change needed.
        // The mention is only injected on the first turn (when case is created).
        val prefixedText = when (case.status) {
            CaseStatus.PENDING -> "@${config.name} $text"
            else -> text
        }

        val actor = Actor(
            id = "a2a:${message.messageId}",
            displayName = "A2A Client",
            role = ActorRole.USER,
        )

        caseService.addMessage(
            caseId = case.id,
            actor = actor,
            content = listOf(MessageContent.Text(prefixedText)),
            answerToEventId = null,
            sessionContext = mapOf(
                "a2a.messageId" to message.messageId,
                "a2a.contextId" to (message.contextId ?: case.id.toString()),
            ),
        )

        return buildTaskSnapshot(case, includeHistory = false)
    }

    /**
     * Build an [A2ATask] snapshot for an existing case.
     * Prototype: history and artifacts are not populated — see docs/a2a.md.
     */
    fun getTask(caseId: UUID): A2ATask {
        val case = caseService.findById(caseId)
            ?: throw ResourceNotFoundException("Task $caseId (case) not found")
        return buildTaskSnapshot(case, includeHistory = false)
    }

    /**
     * Cancel a task = kill its case.
     * Rejects the call when the case is already in a terminal state (spec §9.4.5).
     */
    fun cancelTask(caseId: UUID): A2ATask {
        val case = caseService.findById(caseId)
            ?: throw ResourceNotFoundException("Task $caseId (case) not found")
        if (case.status == CaseStatus.KILLED || case.status == CaseStatus.ERROR) {
            error("Task ${case.id} is already in terminal state ${case.status}") // handler maps to TASK_NOT_CANCELABLE
        }
        caseService.killCase(case.id)
        val refreshed = caseService.findById(case.id) ?: case
        return buildTaskSnapshot(refreshed, includeHistory = false)
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    internal fun createCase(namespaceId: UUID, seedText: String): Case {
        val title = seedText.take(80).replace("\n", " ").ifBlank { "A2A task" }
        return caseService.create(
            Case(
                namespaceId = namespaceId,
                status = CaseStatus.PENDING,
                title = title,
            ),
        )
    }

    private fun extractText(message: A2AMessage): String =
        message.parts.joinToString("\n\n") { part ->
            when (part) {
                is A2APart.TextPart -> part.text
                is A2APart.DataPart -> part.data.toString()
                is A2APart.FilePart -> "[file: ${part.file.name ?: part.file.uri ?: "inline"}]"
            }
        }.trim()

    internal fun parseUuidOrNull(s: String): UUID? =
        runCatching { UUID.fromString(s) }.getOrNull()

    private fun buildTaskSnapshot(case: Case, includeHistory: Boolean): A2ATask =
        A2ATask(
            id = case.id.toString(),
            contextId = case.id.toString(), // v1 heuristic: contextId == taskId
            status = CaseEventMapper.buildTaskStatus(case),
            history = if (includeHistory) emptyList() else null,
            artifacts = null,
            metadata = mapOf(
                "agentos.namespaceId" to case.namespaceId.toString(),
                "agentos.title" to case.title,
            ),
        )

    /**
     * Public helper used by the SSE controller: given an initial send-message
     * result, resolve the taskId + contextId for downstream mapping.
     */
    fun taskContext(task: A2ATask): Pair<String, String> = task.id to task.contextId

    /** Expose [A2ATaskState] terminal check for the SSE loop. */
    fun isTerminal(state: A2ATaskState): Boolean = state.isTerminal()

    // -----------------------------------------------------------------
    // Cross-binding helpers used by the REST controller as well.
    // -----------------------------------------------------------------

    /**
     * Send a user message on an existing task (case) — used both by the
     * JSON-RPC path (task follow-up) and the REST path.
     */
    fun sendFollowUp(config: AgentConfig, case: Case, text: String, messageId: String) {
        val actor = Actor(
            id = "a2a:$messageId",
            displayName = "A2A Client",
            role = ActorRole.USER,
        )
        val prefixed = if (case.status == CaseStatus.PENDING) "@${config.name} $text" else text
        caseService.addMessage(
            caseId = case.id,
            actor = actor,
            content = listOf(MessageContent.Text(prefixed)),
            answerToEventId = null,
            sessionContext = mapOf("a2a.messageId" to messageId),
        )
    }

    /** Load a case or throw. */
    fun requireCase(caseId: UUID): Case =
        caseService.findById(caseId)
            ?: throw ResourceNotFoundException("Task $caseId (case) not found")

    /**
     * Return the persisted agent [MessageEvent]s for a case, ordered by
     * timestamp. Used to hydrate the `artifacts` field on task snapshots.
     */
    fun agentMessageEvents(caseId: UUID): List<MessageEvent> =
        caseEventService.findByParent(caseId)
            .filterIsInstance<MessageEvent>()
            .filter { it.actor.role == ActorRole.AGENT }
            .sortedBy { it.timestamp }

    /**
     * Convenience: (existingCase, isNewCase). Creates the case if [taskId] is null.
     * Used by REST message:send / message:stream where we need the "first turn"
     * bit to know whether to inject the `@AgentName` prefix.
     */
    fun getOrCreateCase(namespaceId: UUID, taskId: String?, seedTitle: String): Pair<Case, Boolean> =
        when (val existing = taskId?.let(::parseUuidOrNull)) {
            null -> createCase(namespaceId, seedTitle) to true
            else -> (
                caseService.findById(existing)
                    ?: throw ResourceNotFoundException("Task $existing (case) not found")
            ) to false
        }

    companion object : KLogging()
}

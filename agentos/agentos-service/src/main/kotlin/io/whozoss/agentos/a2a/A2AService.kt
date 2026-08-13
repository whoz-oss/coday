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
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.user.UserService
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
 * Prototype: there is no A2A-*specific* authentication (no API key, no OAuth2 — see
 * docs/a2a.md). All exposed agents are those with `enabled = true` in their namespace.
 *
 * Task-level access control *is* enforced, however: every task created here is granted to
 * the ambient caller identity ([createCase]) and every task read/write/cancel is checked
 * against that identity ([requireCaseWithAccess]). This keeps A2A tasks visible to their
 * owner and prevents a caller from touching another user's case by guessing its UUID.
 */
@Service
class A2AService(
    private val agentConfigService: AgentConfigService,
    private val caseService: CaseService,
    private val namespaceService: NamespaceService,
    private val caseEventService: CaseEventService,
    private val userService: UserService,
    private val permissionService: PermissionService,
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
            else -> requireCaseWithAccess(existingId, Action.WRITE)
        }

        // Force agent selection via @mention. This piggy-backs on the existing
        // CaseServiceImpl.selectAgent path — no core change needed.
        // The mention is only injected on the first turn (when case is created).
        val prefixedText = when (case.status) {
            CaseStatus.PENDING -> "@${config.name} $text"
            else -> text
        }

        caseService.addMessage(
            caseId = case.id,
            actor = currentActor(),
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
     * Build an [A2ATask] snapshot for an existing case the caller can READ.
     * Prototype: history and artifacts are not populated — see docs/a2a.md.
     */
    fun getTask(caseId: UUID): A2ATask {
        val case = requireCaseWithAccess(caseId, Action.READ)
        return buildTaskSnapshot(case, includeHistory = false)
    }

    /**
     * Cancel a task = kill its case. Requires DELETE on the case, mirroring
     * `CaseController.killCase`.
     * Rejects the call when the case is already in a terminal state (spec §9.4.5).
     */
    fun cancelTask(caseId: UUID): A2ATask {
        val case = requireCaseWithAccess(caseId, Action.DELETE)
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

    /**
     * Create the [Case] backing a new A2A task and grant its owner `[:ADMIN]` on it.
     *
     * The grant is **not** optional: `GET /api/cases/by-parentId/{ns}/mine` (the AgentOS
     * drawer) resolves through a direct user↔case edge, so a case created without it is
     * invisible to the very user who triggered the A2A call, and cannot be opened, starred
     * or deleted. This mirrors what `CaseController.create` does for UI-created cases and
     * `CaseServiceImpl.startSubCase` for delegated ones.
     *
     * Failure is strict (unlike `CaseController.create`, which is best-effort): the grant
     * runs outside the Neo4j transaction that persisted the case, so on failure we kill the
     * orphan and surface [A2ATaskProvisioningException] instead of returning a task id the
     * caller owns but nobody can see.
     */
    internal fun createCase(namespaceId: UUID, seedText: String): Case {
        val title = seedText.take(MAX_TITLE_LENGTH).replace("\n", " ").ifBlank { "A2A task" }
        val userId = userService.getCurrentUser().id.toString()
        val case = caseService.create(
            Case(
                namespaceId = namespaceId,
                status = CaseStatus.PENDING,
                title = title,
            ),
        )
        try {
            permissionService.grantPermission(
                userId = userId,
                entityType = EntityType.CASE,
                entityId = case.id.toString(),
                relation = PermissionRelation.ADMIN,
            )
        } catch (e: Exception) {
            logger.error(e) {
                "A2A: auto-ADMIN grant failed for case ${case.id} (user $userId) — killing orphaned task"
            }
            runCatching { caseService.killCase(case.id) }
                .onFailure { killErr ->
                    logger.warn(killErr) { "A2A: failed to kill orphaned case ${case.id} after grant failure" }
                }
            throw A2ATaskProvisioningException(
                "Failed to grant permissions on task ${case.id}: ${e.message}",
                e,
            )
        }
        logger.info { "A2A: user $userId created task ${case.id} in namespace $namespaceId with auto-ADMIN grant" }
        return case
    }

    /**
     * Load a case and assert the caller may perform [action] on it.
     *
     * A denial is reported as [ResourceNotFoundException] — i.e. `TASK_NOT_FOUND` (-32001)
     * over JSON-RPC, HTTP 404 over REST — rather than a 403, so that a caller cannot probe
     * which task UUIDs exist in a namespace. This is the same "hide on access denied" policy
     * the case REST controllers apply through `@HideOnAccessDenied`.
     */
    private fun requireCaseWithAccess(caseId: UUID, action: Action): Case {
        val case = caseService.findById(caseId)
            ?: throw ResourceNotFoundException("Task $caseId (case) not found")
        val userId = userService.getCurrentUser().id.toString()
        val allowed = permissionService.hasPermission(
            userId = userId,
            entityType = EntityType.CASE,
            entityId = caseId.toString(),
            action = action,
        )
        if (!allowed) {
            logger.warn { "A2A: user $userId denied $action on task $caseId — reported as not found" }
            throw ResourceNotFoundException("Task $caseId (case) not found")
        }
        return case
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
        val prefixed = if (case.status == CaseStatus.PENDING) "@${config.name} $text" else text
        caseService.addMessage(
            caseId = case.id,
            actor = currentActor(),
            content = listOf(MessageContent.Text(prefixed)),
            answerToEventId = null,
            sessionContext = mapOf("a2a.messageId" to messageId),
        )
    }

    /**
     * Resolve the [Actor] for the message author of an A2A call.
     *
     * The prototype has no A2A-specific authentication (see docs/a2a.md §4):
     * the identity is whatever [UserService.getCurrentUser] resolves for the
     * incoming request — the OS username in `local` security mode, or the
     * caller identity in `auth` mode. This is required because
     * [io.whozoss.agentos.caseFlow.CaseServiceImpl.runAgent] rejects any case
     * whose last user message doesn't carry a resolvable [UUID] actor id.
     *
     * The same identity owns the task: [createCase] grants it `[:ADMIN]`, and
     * [requireCaseWithAccess] checks against it on every later call.
     */
    private fun currentActor(): Actor {
        val user = userService.getCurrentUser()
        return Actor(
            id = user.id.toString(),
            displayName = user.displayName(),
            role = ActorRole.USER,
        )
    }

    /** Load a case the caller can READ, or throw [ResourceNotFoundException]. */
    fun requireCase(caseId: UUID): Case = requireCaseWithAccess(caseId, Action.READ)

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
            else -> requireCaseWithAccess(existing, Action.WRITE) to false
        }

    companion object : KLogging() {
        /** Cap on the case title derived from the first message's text. */
        private const val MAX_TITLE_LENGTH = 80
    }
}

package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A declarative, quasi-immutable definition of a scheduled case.
 *
 * A [CaseDefinition] answers three questions: **WHO** (targeting via agent + DEPLOYED_TO graph),
 * **WHAT** (agent + prompt), and **WHEN** (cron schedule). It is configuration — it does not
 * represent an execution.
 *
 * ### CaseDefinition vs. CaseInstance (not yet implemented)
 *
 * | Concept          | Role                                                                    |
 * |------------------|-------------------------------------------------------------------------|
 * | [CaseDefinition] | Stable configuration: who, what, when. Changes rarely.                 |
 * | `CaseInstance`   | Volatile execution record: status, logs, errors, linked cases.          |
 *
 * ### Scope model
 *
 * Scope is determined by the `(namespaceId, userId)` pair, mirroring the [Prompt] pattern:
 *
 * | namespaceId | userId | Scope          | Priority |
 * |-------------|--------|----------------|----------|
 * | null        | null   | Platform       | 0 (lowest) |
 * | null        | set    | User-global    | 1 |
 * | set         | null   | Namespace      | 2 |
 * | set         | set    | User×Namespace | 3 (highest) |
 *
 * Platform-level case definitions (namespaceId = null) are managed by super-admins.
 * Higher-priority layers override lower ones when resolving the effective set via
 * `findEffective` (same name → higher layer wins).
 *
 * ### Agent targeting model
 *
 * Unlike [io.whozoss.agentos.prompt.Prompt], [agentConfigId] is **always required**.
 * A case definition always targets a specific agent. Access control at read-time
 * is enforced by traversing the `DEPLOYED_TO` graph: the user must be super-admin
 * OR a member of a UserGroup to which the agent is deployed.
 *
 * ### Prompt reference
 *
 * [promptId] references an existing [io.whozoss.agentos.prompt.Prompt] that provides
 * the opening message sent to the agent when the scheduled case fires.
 * The Prompt MUST NOT have an `agentConfigId` — only generic prompts (agentConfigId = null)
 * are allowed. The prompt is created and managed automatically by the backend; its ID is an
 * internal implementation detail not exposed in the public API.
 *
 * ### Cron format
 *
 * 5-field standard cron: `minute hour day-of-month month day-of-week`
 * - DAILY at 09:00 UTC  → `0 9 * * *`
 * - WEEKLY on MON at 09:00 UTC → `0 9 * * MON`
 *
 * The conversion between the API's `frequency + dayOfWeek + timeUtc` representation
 * and the cron string is performed in [CronExpressionConverter].
 *
 * ### Name slug convention
 *
 * On creation, [name] must match the slug pattern `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`.
 * This is validated in [CaseDefinitionServiceImpl.create] and is NOT retroactively
 * enforced on existing records.
 *
 * ### Cascade delete note
 *
 * TODO: Cascade delete — When an AgentConfig is soft-deleted, its linked CaseDefinitions
 * should also be soft-deleted. This is deferred because the cascade strategy needs to be
 * designed holistically when other scheduler types (with convergent structure) are introduced.
 * See also: PromptServiceImpl has softDeleteByAgentConfigId() which could serve as a pattern.
 */
data class CaseDefinition(
    override val metadata: EntityMetadata = EntityMetadata(),
    /** Namespace the definition belongs to. Null for platform-level. */
    val namespaceId: UUID? = null,
    /** Optional: user-specific overlay. Null for shared (namespace or platform-wide). */
    val userId: UUID? = null,
    /** Reference to an AgentConfig. Always required — a CaseDefinition always targets an agent. */
    val agentConfigId: UUID,
    /**
     * Reference to a Prompt entity providing the opening message for the scheduled case.
     * The Prompt MUST NOT have an agentConfigId — only generic prompts are allowed.
     * Managed internally by the backend; not exposed in the public API.
     */
    val promptId: UUID,
    val name: String,
    val description: String? = null,
    /**
     * Standard 5-field cron expression (minute hour dom month dow).
     * Built from `frequency + dayOfWeek + timeUtc` by [CronExpressionConverter.toCron];
     * parsed back by [CronExpressionConverter.fromCron].
     */
    val cronExpression: String,
    val enabled: Boolean = true,
) : Entity

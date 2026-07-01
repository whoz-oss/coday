package io.whozoss.agentos.caseDefinition

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * A declarative, quasi-immutable definition of a scheduled case.
 *
 * A [CaseDefinition] answers three questions: **WHO** (targeting), **WHAT** (agent + prompt),
 * and **WHEN** (cron schedule). It is configuration — it does not represent an execution.
 *
 * ### CaseDefinition vs. CaseInstance (not yet implemented)
 *
 * | Concept          | Role                                                                    |
 * |------------------|-------------------------------------------------------------------------|
 * | [CaseDefinition] | Stable configuration: who, what, when. Changes rarely.                 |
 * | `CaseInstance`   | Volatile execution record: status, logs, errors, linked cases.          |
 *
 * `CaseInstance` represents an execution: it carries status, logs, errors, and links
 * to the cases created. The relationship and cardinality between [CaseDefinition] and
 * `CaseInstance` will be defined in step 2.
 *
 * This separation keeps [CaseDefinition] lean and auditable — its history reflects
 * intentional configuration changes, not the noise of individual executions.
 *
 * **Step 1 — purely declarative CRUD.** No triggering, no scheduler, no @Scheduled.
 * The `CaseInstance` entity and the scheduling engine will be added in a later step.
 *
 * ### Targeting model
 *
 * [namespaceId] is always required. [userGroupId] and [userId] are optional refinements
 * that narrow the target population to a group or a specific user within that namespace.
 * They are mutually exclusive — setting both is rejected by [init].
 *
 * | namespaceId | userGroupId | userId | Meaning                          |
 * |-------------|-------------|--------|----------------------------------|
 * | set         | null        | null   | All users in the namespace       |
 * | set         | set         | null   | All users in the group           |
 * | set         | null        | set    | A specific user in the namespace |
 * | set         | set         | set    | ❌ invalid — rejected by init    |
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
 * @JsonIgnoreProperties(ignoreUnknown = true) is required because [Entity] exposes a
 * computed `id` property that Jackson serialises on write but cannot find in the
 * constructor on read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseDefinition(
    override val metadata: EntityMetadata = EntityMetadata(),
    /** Namespace the definition belongs to. Always required. */
    val namespaceId: UUID,
    /** Optional: narrows to a specific user group within the namespace. */
    val userGroupId: UUID? = null,
    /** Optional: narrows to a specific user within the namespace. */
    val userId: UUID? = null,
    val name: String,
    val description: String? = null,
    /** Reference to an agent config id. */
    val agentId: UUID,
    /** Opening message sent to the agent when the case fires. */
    val prompt: String,
    /**
     * Standard 5-field cron expression (minute hour dom month dow).
     * Built from `frequency + dayOfWeek + timeUtc` by [CronExpressionConverter.toCron];
     * parsed back by [CronExpressionConverter.fromCron].
     */
    val cronExpression: String,
    val enabled: Boolean = true,
) : Entity {
    init {
        require(userGroupId == null || userId == null) {
            "userGroupId and userId cannot both be set on a CaseDefinition"
        }
    }
}

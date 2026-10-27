package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent data model representing a case.
 *
 * Implements [Entity] for standard CRUD operations.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is required because the [Entity]
 * interface exposes a computed `id` property (delegating to metadata.id) which
 * Jackson serialises into the JSON but is not a constructor parameter — so
 * deserialisation must silently skip it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Case(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val status: CaseStatus = CaseStatus.CREATED,
    val title: String = "Case ${metadata.id}",
    /**
     * Id of the parent case when this case was created by a delegation tool.
     * Null for top-level cases created directly by a user.
     */
    val parentCaseId: UUID? = null,
    /**
     * Id of the [io.whozoss.agentos.scheduledPrompt.ScheduledPrompt] that triggered this case.
     * Null for cases started by a human user or a delegation tool.
     */
    val scheduledPromptId: UUID? = null,
    /**
     * Id of the case whose agent started this one with the `FreeCaseStarter` tool
     * ([io.whozoss.agentos.casePlugin.CaseStarterService]). Null for cases started otherwise.
     *
     * A trace of provenance, unlike [parentCaseId]: a started case is a top-level case in every other
     * respect (own lifecycle, workspace, cost and delegation depth). The only thing that reads it is the rule
     * that keeps a started case, and the cases delegated from it, from starting another one.
     */
    val originCaseId: UUID? = null,
    /**
     * Per-case override of the run cost threshold, expressed in the platform's currency unit.
     *
     * **`null` means "inherit from the namespace or platform default", never "no limit" or "zero".**
     *
     * This field serves two purposes:
     * - **Upfront configuration**: set before a run when the caller knows the work ahead will
     *   be costly (e.g. a large refactoring session). Lets the user pre-authorise a higher spend
     *   without being interrupted mid-run.
     * - **Negotiation materialisation**: written by the cost-enforcement mechanism when the user chooses to continue after a threshold breach. A new absolute
     *   value is persisted, never a delta or a multiplier, so the stored fact is independent of
     *   any platform or namespace configuration that may change later.
     */
    val runCostThreshold: Double? = null,
    /**
     * Per-case override of the reasoning effort of the agents that run in this case.
     *
     * **`null` means "use the effort of each agent's model"** ([io.whozoss.agentos.sdk.aiProvider.AiModel.reasoningEffort]).
     * Read at the start of each agent run (each step of a message, redirects included), never during one.
     * Applies only to agents whose model has a reasoning effort configured above NONE: setting a level on the
     * model is how an admin declares that the model reasons, which also rules out Anthropic. Sub-cases do not
     * inherit it.
     */
    val reasoningEffort: ReasoningEffort? = null,
) : Entity

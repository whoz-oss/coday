package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort

/**
 * Request body for `PUT /api/cases/{id}/reasoning-effort`.
 *
 * Sets the case-level override of the reasoning effort. A null [reasoningEffort] removes the
 * override, so each agent runs with the effort of its model again. A dedicated endpoint is
 * needed because `PUT /api/cases/{id}` reads an absent field as "keep the current value".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseReasoningEffortRequest(
    val reasoningEffort: ReasoningEffort? = null,
)

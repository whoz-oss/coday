package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Request body for `PUT /api/cases/{id}/model`.
 *
 * Sets the case-level override of the AI model (an alias or an API model name). A null [modelName] removes the
 * override, so each agent runs with its own model again. A dedicated endpoint is needed because
 * `PUT /api/cases/{id}` reads an absent field as "keep the current value".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseModelRequest(
    val modelName: String? = null,
)

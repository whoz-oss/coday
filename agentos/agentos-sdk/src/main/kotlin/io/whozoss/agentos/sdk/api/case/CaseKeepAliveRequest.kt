package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Request body for `PUT /api/cases/{id}/keep-alive`.
 *
 * Turns the prompt cache keep-alive of the case on ([keepAlive] true) or off. A dedicated endpoint, like the
 * reasoning effort's, because `PUT /api/cases/{id}` reads an absent field as "keep the current value".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseKeepAliveRequest(
    val keepAlive: Boolean = false,
)

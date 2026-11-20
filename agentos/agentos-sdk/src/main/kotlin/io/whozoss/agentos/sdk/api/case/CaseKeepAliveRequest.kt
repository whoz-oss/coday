package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Request body for `PUT /api/cases/{id}/keep-alive`.
 *
 * Turns the prompt cache keep-alive of the case on ([keepAlive] true) or off. A dedicated endpoint, like the
 * reasoning effort's, because `PUT /api/cases/{id}` reads an absent field as "keep the current value". The value is
 * required: a body without it (`{}`, or null) is refused with a 400 rather than read as "off".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseKeepAliveRequest(
    @field:Schema(description = "True to keep the prompt cache warm while the case is idle, false to stop.", requiredMode = Schema.RequiredMode.REQUIRED)
    val keepAlive: Boolean?,
)

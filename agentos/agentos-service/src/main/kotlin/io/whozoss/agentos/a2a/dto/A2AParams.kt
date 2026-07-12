package io.whozoss.agentos.a2a.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Params for `message/send` and `message/stream` (spec §9.4.1 / §9.4.2, §3.2.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SendMessageParams(
    val message: A2AMessage,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val configuration: SendMessageConfiguration? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val metadata: Map<String, Any?>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SendMessageConfiguration(
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val acceptedOutputModes: List<String>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val blocking: Boolean? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val historyLength: Int? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val pushNotificationConfig: Map<String, Any?>? = null,
)

/**
 * Params for `tasks/get` (spec §9.4.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TaskQueryParams(
    val id: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val historyLength: Int? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val metadata: Map<String, Any?>? = null,
)

/**
 * Params for `tasks/cancel` (spec §9.4.5) and `tasks/resubscribe`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TaskIdParams(
    val id: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val metadata: Map<String, Any?>? = null,
)

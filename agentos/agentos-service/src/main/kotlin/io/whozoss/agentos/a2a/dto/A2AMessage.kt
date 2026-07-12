package io.whozoss.agentos.a2a.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * A2A `Message` — a single communication turn (spec §4.1.4).
 *
 * The `role` is either `"user"` or `"agent"`. `messageId` is client-generated on
 * outbound turns; the server echoes/generates one for its own agent turns.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class A2AMessage(
    val role: String, // "user" | "agent"
    val parts: List<A2APart>,
    val messageId: String,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val taskId: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val contextId: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val referenceTaskIds: List<String>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val metadata: Map<String, Any?>? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val extensions: List<String>? = null,
)

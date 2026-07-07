package io.whozoss.agentos.sdk.api.aiProvider

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP DTO for AiModel entities — used as both request body and response body on
 * the `/api/ai-models` endpoints.
 *
 * An AiModel belongs to an [AiProviderDto] (via [aiProviderId]) and describes how to
 * invoke a particular model: its real API name, optional stable alias, and inference
 * parameters.
 *
 * [namespaceId] and [userId] are read-only from the client perspective: they are
 * resolved server-side from the parent [AiProviderDto] at creation time and must not
 * be overridden by the caller.
 *
 * [alias] is an optional stable contract name (e.g. `"SMALL"`, `"BIG"`) that agent
 * definitions can reference without knowing the underlying model.
 *
 * [priority] controls resolution order when multiple configs share the same alias or
 * apiModelName within a namespace. Higher value wins. Defaults to 0.
 */
@Schema(name = "AiModel")
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiModelDto(
    val id: UUID? = null,
    @field:NotNull
    val aiProviderId: UUID?,
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    @field:NotBlank
    val apiModelName: String,
    val description: String? = null,
    val alias: String? = null,
    val priority: Int = 0,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

package io.whozoss.agentos.llmConfig

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for [LlmConfig] entities.
 *
 * Kept separate from the domain entity so the two can evolve independently.
 *
 * [apiKey] is write-only in practice: on read it is always returned masked
 * (see [LlmConfigController.toResource]). On write, if the value contains the
 * mask sentinel "****", the controller treats it as "unchanged" and preserves
 * the persisted key (see [LlmConfigController.update]).
 *
 * Annotated with @Schema(name = "LlmConfig") so the generated OpenAPI spec uses
 * the clean name instead of "LlmConfigResource".
 */
@Schema(name = "LlmConfig")
data class LlmConfigResource(
    val id: UUID? = null,
    @field:NotNull
    val namespaceId: UUID?,
    @field:NotBlank
    val name: String,
    @field:NotNull
    val apiType: AiApiType?,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    @field:Valid
    val models: List<LlmModelEntryResource> = emptyList(),
)

/**
 * HTTP resource (DTO) for [LlmModelEntry] value objects.
 *
 * Annotated with @Schema(name = "LlmModelEntry") for a clean OpenAPI schema name.
 */
@Schema(name = "LlmModelEntry")
data class LlmModelEntryResource(
    @field:NotBlank
    val apiName: String,
    val alias: String? = null,
    val displayName: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

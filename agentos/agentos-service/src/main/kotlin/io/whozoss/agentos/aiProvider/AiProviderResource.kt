package io.whozoss.agentos.aiProvider

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for [AiProvider] entities.
 *
 * At least one of [namespaceId] / [userId] must be non-null. This constraint is
 * enforced in [AiProviderServiceImpl] rather than via Bean Validation annotations,
 * because @NotNull cannot express an "either/or" condition across two fields.
 *
 * [apiKey] is write-only in practice: on read it is always returned masked.
 * On write, if the value contains the mask sentinel "****", the controller treats
 * it as "unchanged" and preserves the persisted key.
 *
 * Models are managed as independent [AiModel]
 * entities via their own endpoints — they are not embedded in this resource.
 */
@Schema(name = "AiProvider")
data class AiProviderResource(
    val id: UUID? = null,
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:NotNull
    val apiType: AiApiType?,
    val baseUrl: String? = null,
    val apiKey: String? = null,
)

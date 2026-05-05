package io.whozoss.agentos.aiModel

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * HTTP resource (DTO) for user-scoped [io.whozoss.agentos.sdk.aiProvider.AiModel] entities.
 *
 * [namespaceId] and [userId] are server-side-resolved from the parent [io.whozoss.agentos.aiProvider.AiProvider]
 * at creation time (via [io.whozoss.agentos.aiModel.AiModelServiceImpl.create] dénormalisation) and are
 * ignored on writes. [aiProviderId] is immutable after creation.
 */
@Schema(name = "UserAiModel")
data class UserAiModelResource(
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

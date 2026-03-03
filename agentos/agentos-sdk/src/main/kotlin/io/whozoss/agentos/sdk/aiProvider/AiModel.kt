package io.whozoss.agentos.sdk.aiProvider

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata

/**
 * Data structure defining an AI model configuration.
 *
 * An AiModel combines a reference to an AiProvider with model-specific settings.
 * It is the unit used to configure an agent's AI backend.
 *
 * @property name The model's display name (also used as the agent name)
 * @property description Description of the model for users and other agents
 * @property modelName The model identifier to use with the provider (e.g., "gpt-4o", "claude-3-5-sonnet")
 * @property providerName Name of the AiProvider to use (must match a registered provider)
 * @property temperature Optional temperature override (0.0 to 2.0); uses provider default if null
 * @property maxTokens Optional max output tokens override; uses provider default if null
 * @property instructions System instructions/prompt for the agent (optional)
 */
data class AiModel(
    override val metadata: EntityMetadata = EntityMetadata(),
    val name: String,
    val description: String,
    val modelName: String,
    val providerName: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val instructions: String? = null,
) : Entity

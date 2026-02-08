package io.whozoss.agentos.sdk.aiProvider

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata

data class AiProvider(
    override val metadata: EntityMetadata = EntityMetadata(),
    val name: String,
    val apiType: AiApiType,
    val baseUrl: String,
    val description: String? = null,
    val defaultApiKey: String? = null,
    val baseModel: String? = null,
    val temperature: Double = 1.0,
    val maxTokens: Int? = null,
) : Entity

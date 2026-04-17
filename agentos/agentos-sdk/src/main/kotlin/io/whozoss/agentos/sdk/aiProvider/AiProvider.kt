package io.whozoss.agentos.sdk.aiProvider

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent AI provider configuration.
 *
 * Scoped to a namespace, a user, or both — at least one of [namespaceId] / [userId]
 * must be non-null. This constraint is enforced by [AiProviderServiceImpl.create].
 *
 * - namespace-only: shared provider config for all users of a namespace
 * - user-only: personal provider config (e.g. a user's own Anthropic key)
 * - both: a user-specific override within a namespace
 *
 * The models available under this provider are managed as separate [AiModelConfig]
 * entities (parent: this config's id).
 *
 * Uniqueness constraint: (namespaceId, userId, name) must be unique — enforced by
 * [AiProviderServiceImpl].
 *
 * [apiKey] is stored in clear text internally but is always masked in API responses
 * via [AiProviderController.toResource]. On update, a masked value sent by the client
 * is detected and replaced with the persisted original (see [AiProviderController.update]).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AiProvider(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    val name: String,
    val description: String? = null,
    val apiType: AiApiType,
    val baseUrl: String? = null,
    val apiKey: String? = null,
) : Entity

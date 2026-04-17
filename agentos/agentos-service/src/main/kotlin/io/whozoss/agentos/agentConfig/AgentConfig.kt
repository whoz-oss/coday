package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent configuration of an agent within a namespace.
 *
 * An AgentConfig defines how an agent behaves: its identity (name, description),
 * its system-level instructions, and which AI model it should use.
 *
 * The [modelName] field accepts either a direct model name or an alias defined
 * by an AiProvider — resolution is deferred to the runtime layer.
 *
 * Scoped under a Namespace via [namespaceId].
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is required because the [Entity]
 * interface exposes a computed `id` property that Jackson serialises but which
 * is not a constructor parameter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentConfig(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val modelName: String? = null,
) : Entity

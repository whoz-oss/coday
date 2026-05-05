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
 * [integrations] is an optional map from integration name to an optional list of
 * allowed tool names. When null, the agent receives all tools available in the
 * namespace (no filtering). When set, only tools whose integration key matches an
 * entry in this map are given to the agent. A null list for a given key means all
 * tools from that integration are allowed; a non-null list restricts to exactly
 * those tool names (or suffixes for multi-instance tools named `CONFIG__tool`).
 *
 * Examples (from a Coday-style agent YAML):
 * ```yaml
 * integrations:
 *   FILES:            # all FILE_ACCESS tools
 *   JIRA:
 *     - GetIssue      # only the GetIssue tool from JIRA
 * ```
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
    /**
     * Optional tool-access filter. null = no restriction (all namespace tools).
     * Map key = integration name (matches [IntegrationConfig.name] or
     * [ToolPlugin.integrationType] for config-less plugins).
     * Map value = allowed tool names, or null for all tools of that integration.
     */
    val integrations: Map<String, List<String>?>? = null,
) : Entity

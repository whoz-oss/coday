package io.whozoss.agentos.namespace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata

/**
 * Model representing a namespace — the top-level organizational unit.
 *
 * A namespace groups cases together and corresponds to a logical domain or project scope.
 *
 * [configPath] is an optional filesystem path pointing to a directory that contains
 * base configuration for this namespace (agent definitions, tool configs, etc.).
 * Analogous to `projectPath` in Coday: it tells the filesystem plugin where to resolve
 * namespace-scoped resources. When absent, no filesystem-based configuration is loaded.
 *
 * [defaultAgentName] is the logical name of the agent to invoke when no explicit
 * `@mention` is present in a user message. Resolved at runtime by [AgentService]
 * via name matching against [AgentConfig] entries in this namespace. When null,
 * sending a message without an `@mention` produces an explicit error rather than
 * silently routing to an arbitrary agent.
 *
 * Implements Entity for standard CRUD operations.
 * No parent entity — namespaces are root-level.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Namespace(
    override val metadata: EntityMetadata = EntityMetadata(),
    val name: String,
    val description: String? = null,
    val configPath: String? = null,
    val externalId: String? = null,
    val defaultAgentName: String? = null,
) : Entity

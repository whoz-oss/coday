package io.whozoss.agentos.namespace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata

/**
 * Model representing a namespace — the top-level organizational unit.
 *
 * A namespace groups cases together and corresponds to a logical domain or project scope.
 *
 * Implements Entity for standard CRUD operations.
 * No parent entity — namespaces are root-level.
 *
 * @property fileRoots Optional map of scope names to absolute file system paths.
 *                     Used by file tools to resolve prefixed paths (e.g., "project" -> "/workspace/myproject").
 *                     The map key is the scope identifier (e.g., "project", "exchange").
 *                     Nullable for backward compatibility with existing namespaces.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Namespace(
    override val metadata: EntityMetadata = EntityMetadata(),
    val name: String,
    val description: String? = null,
    val fileRoots: Map<String, String>? = null,
) : Entity

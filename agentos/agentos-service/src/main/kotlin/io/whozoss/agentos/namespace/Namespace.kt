package io.whozoss.agentos.namespace

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata

/**
 * Model representing a namespace — the top-level organizational unit.
 *
 * A namespace groups cases together and corresponds to a project in Coday.
 * Rename to Project (or keep as Namespace) is deferred.
 *
 * Implements Entity for standard CRUD operations.
 * No parent entity — namespaces are root-level.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Namespace(
    override val metadata: EntityMetadata = EntityMetadata(),
    val name: String,
    val description: String? = null,
) : Entity

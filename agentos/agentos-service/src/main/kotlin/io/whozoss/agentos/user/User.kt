package io.whozoss.agentos.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata

/**
 * Persistent data model representing a user in AgentOS.
 *
 * [externalId] is the identity provider key used to look up a user from an
 * incoming HTTP request (e.g. email resolved from a Cloudflare JWT or an
 * x-forwarded-email header). It is intentionally separate from [metadata.id]
 * so that the internal UUID stays stable even if the external identity changes.
 *
 * Implements [Entity] for standard CRUD operations.
 * No parent entity — users are root-level.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class User(
    override val metadata: EntityMetadata = EntityMetadata(),
    val externalId: String,
    val email: String = "",
    val firstname: String? = null,
    val lastname: String? = null,
    val bio: String? = null,
    val isRoot: Boolean = false,
) : Entity

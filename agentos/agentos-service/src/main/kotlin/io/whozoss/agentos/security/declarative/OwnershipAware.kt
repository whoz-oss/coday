package io.whozoss.agentos.security.declarative

import io.whozoss.agentos.permissions.EntityType
import java.util.UUID

/**
 * Implemented by entity services whose authz model supports an ownership branch
 * (`entity.userId == auth.userId`) in addition to the namespace-membership path.
 *
 * Spring auto-collects all implementations so [OwnershipResolver] does not need
 * to know about each service — new scope-aware entities just implement this interface.
 */
interface OwnershipAware {
    val ownershipEntityType: EntityType
    fun resolveOwner(targetId: UUID): UUID?
}

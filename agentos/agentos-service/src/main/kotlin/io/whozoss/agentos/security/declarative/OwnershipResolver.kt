package io.whozoss.agentos.security.declarative

import io.whozoss.agentos.permissions.EntityType
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves the owner (`userId`) of a scope-aware entity by id, for the ownership
 * branch of [AgentOsPermissionEvaluator].
 *
 * Auto-collects all [OwnershipAware] beans so new scope-aware entities just need
 * to implement the interface — no change required here (Open/Closed Principle).
 */
@Component
class OwnershipResolver(
    resolvers: List<OwnershipAware>,
) {
    private val resolverMap: Map<EntityType, OwnershipAware> =
        resolvers.associateBy { it.ownershipEntityType }

    val supportedTypes: Set<EntityType> get() = resolverMap.keys

    fun resolveOwner(entityType: EntityType, targetId: UUID): UUID? =
        resolverMap[entityType]?.resolveOwner(targetId)
}

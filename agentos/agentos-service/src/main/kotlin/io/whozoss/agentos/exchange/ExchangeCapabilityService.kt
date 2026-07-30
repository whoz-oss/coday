package io.whozoss.agentos.exchange

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.exchange.ExchangeCapability
import org.springframework.stereotype.Service

/**
 * Single source of truth for the exchange scope permission checks ([canRead], [canWrite]) and the
 * resulting [ExchangeCapability].
 *
 * Consumed both by the REST layer ([ExchangeController] — the manifest's server-computed capability)
 * and by the agent tool-grant path ([io.whozoss.agentos.agent.AgentServiceImpl.buildExchangeTools] —
 * the namespace grant's READ gate and its `readOnly` flag), so the two never diverge: the rules are
 * defined here once. Read == the entity's READ permission, write == the entity's WRITE permission
 * (Case/Namespace ADMIN, super-admin included), per the permission model.
 */
@Service
class ExchangeCapabilityService(
    private val permissionService: PermissionService,
) {
    /** True when [userId] may read the given exchange scope entity. */
    fun canRead(
        userId: String,
        entityType: EntityType,
        entityId: String,
    ): Boolean = permissionService.hasPermission(userId, entityType, entityId, Action.READ)

    /** True when [userId] may write the given exchange scope entity. */
    fun canWrite(
        userId: String,
        entityType: EntityType,
        entityId: String,
    ): Boolean = permissionService.hasPermission(userId, entityType, entityId, Action.WRITE)

    /**
     * The caller's capability over the scope. READ is the floor (the REST callers already hold READ
     * via `@PreAuthorize`); upgrades to READ_WRITE when [canWrite] holds.
     */
    fun capability(
        userId: String,
        entityType: EntityType,
        entityId: String,
    ): ExchangeCapability =
        if (canWrite(userId, entityType, entityId)) ExchangeCapability.READ_WRITE else ExchangeCapability.READ
}

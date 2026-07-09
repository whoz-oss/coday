package io.whozoss.agentos.exchange

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import org.springframework.stereotype.Service

/**
 * Single source of truth for "can this user write this exchange scope" and the resulting
 * [ExchangeCapability].
 *
 * Consumed both by the REST layer ([ExchangeController] — the manifest's server-computed capability)
 * and by the agent tool-grant path ([io.whozoss.agentos.agent.AgentServiceImpl.buildExchangeTools] —
 * the namespace grant's `readOnly` flag), so the two never diverge: the write rule is defined here
 * once. Write == the entity's WRITE permission (Case/Namespace ADMIN, super-admin included), per the
 * permission model.
 */
@Service
class ExchangeCapabilityService(
    private val permissionService: PermissionService,
) {
    /** True when [userId] may write the given exchange scope entity. */
    fun canWrite(
        userId: String,
        entityType: EntityType,
        entityId: String,
    ): Boolean = permissionService.hasPermission(userId, entityType, entityId, Action.WRITE)

    /**
     * The caller's capability over the scope. READ is the floor (callers already hold READ via
     * `@PreAuthorize`); upgrades to READ_WRITE when [canWrite] holds.
     */
    fun capability(
        userId: String,
        entityType: EntityType,
        entityId: String,
    ): ExchangeCapability =
        if (canWrite(userId, entityType, entityId)) ExchangeCapability.READ_WRITE else ExchangeCapability.READ
}

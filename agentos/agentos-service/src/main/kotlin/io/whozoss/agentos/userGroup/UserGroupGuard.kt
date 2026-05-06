package io.whozoss.agentos.userGroup

import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

/**
 * Authorization guard for [UserGroup] endpoints.
 *
 * `UserGroup` is namespace-scoped (each row carries `namespaceId`); authorization piggybacks
 * on the namespace ADMIN/MEMBER relations:
 * - Read (search) requires READ on the resolved namespace (= MEMBER or ADMIN).
 * - Write (create) requires WRITE on the resolved namespace (= ADMIN).
 *
 * The HTTP API exposes `namespaceExternalId` (federation identifier) rather than the internal
 * `namespaceId` UUID, so this guard resolves the external id to a namespace before the
 * permission check. A namespace that does not exist (or has no row at all under that external
 * id) yields `false` regardless of the caller — we never leak existence via a different status
 * code.
 */
@Component
class UserGroupGuard(
    private val namespaceService: NamespaceService,
    private val permissionService: PermissionService,
) {
    fun canRead(namespaceExternalId: String, auth: Authentication): Boolean =
        check(namespaceExternalId, auth, Action.READ)

    fun canCreate(namespaceExternalId: String, auth: Authentication): Boolean =
        check(namespaceExternalId, auth, Action.WRITE)

    private fun check(
        namespaceExternalId: String,
        auth: Authentication,
        action: Action,
    ): Boolean {
        val me = auth.name ?: return false
        val namespace = namespaceService.findByExternalId(namespaceExternalId) ?: return false
        return permissionService.hasPermission(me, EntityType.NAMESPACE, namespace.id.toString(), action)
    }
}

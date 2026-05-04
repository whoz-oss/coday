package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component

/**
 * Authorization guard for user-scoped [IntegrationConfig] entities.
 *
 * Authorization is **ownership-based**: a row is accessible iff `cfg.userId == auth.name`.
 * This is intentionally distinct from the namespace-membership model exposed by
 * [io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator]
 * (which evaluates `hasPermission(...)` SpEL via [PermissionService]) — user-scoped overrides
 * never participate in the namespace ADMIN/MEMBER relations.
 *
 * Returns booleans rather than throwing. Translation to HTTP semantics
 * ([org.springframework.security.access.AccessDeniedException] → 404 via
 * [io.whozoss.agentos.security.declarative.HideOnAccessDenied]) is the controller's job.
 */
@Component
class UserIntegrationConfigGuard(
    private val permissionService: PermissionService,
) {
    /**
     * True iff [auth] owns the given config (`cfg.userId.toString() == auth.name`).
     * Note: [Authentication.getName] returns the user's UUID-as-String — see
     * [io.whozoss.agentos.security.declarative.AgentOsAuthentication.getName].
     */
    fun canRead(cfg: IntegrationConfig, auth: Authentication): Boolean = isOwner(cfg, auth)

    /**
     * True iff [auth] owns the given config. Same predicate as [canRead] in this story —
     * we keep the methods distinct so a future evolution (e.g. read-only delegation) can
     * tighten write authorization without touching read paths.
     */
    fun canModify(cfg: IntegrationConfig, auth: Authentication): Boolean = isOwner(cfg, auth)

    /**
     * True iff the caller may create the given target row:
     * - user-global (`namespaceId == null`) → authentication suffices.
     * - user × namespace (`namespaceId != null`) → the caller must hold READ on the namespace
     *   (i.e. be MEMBER or ADMIN). Without that, a stranger could materialize an override
     *   inside a namespace they cannot otherwise reach.
     */
    fun canCreate(target: IntegrationConfig, auth: Authentication): Boolean {
        val me = auth.name ?: return false
        return when (val nsId = target.namespaceId) {
            null -> true
            else -> permissionService.hasPermission(me, EntityType.NAMESPACE, nsId.toString(), Action.READ)
        }
    }

    private fun isOwner(cfg: IntegrationConfig, auth: Authentication): Boolean {
        // auth.name is a String; cfg.userId is a UUID. Compare as String to avoid the
        // silently-false UUID-vs-String comparison trap.
        val me = auth.name ?: return false
        return cfg.userId?.toString() == me
    }
}

package io.whozoss.agentos.security.declarative

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.Entity
import mu.KLogging
import org.springframework.security.access.PermissionEvaluator
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import java.io.Serializable

/**
 * Custom Spring Security [PermissionEvaluator] that bridges the SpEL keyword
 * `hasPermission(...)` to AgentOS's existing [PermissionService].
 *
 * Wired into the SpEL evaluation pipeline by [MethodSecurityConfig.methodSecurityExpressionHandler],
 * this allows declarative checks like:
 *
 * ```
 * @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
 * fun update(@PathVariable id: UUID, ...)
 * ```
 *
 * The user identifier is read from [Authentication.getName] — populated by
 * [AgentOsAuthenticationFilter] to be the User's UUID as a String. This avoids
 * a second [io.whozoss.agentos.user.UserService.getCurrentUser] round-trip
 * during permission evaluation.
 *
 * **String → typed conversion** : SpEL annotations cannot ergonomically reference
 * Kotlin enum constants, so [targetType] arrives as a `String` and is converted to
 * [EntityType] here. Unknown labels (typos in `@PreAuthorize`) fail closed (`false`)
 * AND emit a WARN log so the misconfiguration surfaces in production logs (story 5-5
 * AC6 — without this, typos were silently ignored).
 *
 * The permission string must match enum values of [Action] (READ / WRITE / DELETE);
 * unknown values yield `false` (already fail-closed by `runCatching`).
 */
@Component
class AgentOsPermissionEvaluator(
    private val permissionService: PermissionService,
) : PermissionEvaluator {

    override fun hasPermission(
        authentication: Authentication?,
        targetId: Serializable?,
        targetType: String?,
        permission: Any?,
    ): Boolean {
        if (authentication == null || targetId == null || targetType == null || permission == null) return false
        val userId = authentication.name ?: return false
        val action = runCatching { Action.valueOf(permission.toString()) }.getOrNull() ?: return false

        val entityType = EntityType.fromLabel(targetType)
        if (entityType == null) {
            logger.warn {
                "[AgentOsPermissionEvaluator] Unknown entity label '$targetType' in @PreAuthorize SpEL — " +
                    "fail-closed (returning false). Check for typos against EntityType.entries."
            }
            return false
        }

        return permissionService.hasPermission(userId, entityType, targetId.toString(), action)
    }

    override fun hasPermission(
        authentication: Authentication?,
        targetDomainObject: Any?,
        permission: Any?,
    ): Boolean {
        if (targetDomainObject !is Entity) return false
        val type = targetDomainObject::class.simpleName ?: return false
        return hasPermission(authentication, targetDomainObject.id, type, permission)
    }

    companion object : KLogging()
}

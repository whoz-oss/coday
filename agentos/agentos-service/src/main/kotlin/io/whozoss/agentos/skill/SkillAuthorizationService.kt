package io.whozoss.agentos.skill

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Bridges Spring Security SpEL checks to filesystem-backed [Skill]s, which have no
 * Neo4j node and are therefore invisible to [PermissionService.hasPermission]'s
 * direct/transitive `Skill` entity lookups.
 *
 * [SkillService] (via the [FilesystemSkillRepository] decorator) resolves both
 * persisted and filesystem-backed skills uniformly, so [canRead] can always obtain
 * the skill's [Skill.namespaceId] and authorize against the OWNING NAMESPACE instead
 * of the (non-existent) Skill node.
 *
 * Used as a fallback branch in `@PreAuthorize` SpEL, e.g.:
 * ```
 * @PreAuthorize("hasPermission(#id, 'Skill', 'READ') or @skillAuthorizationService.canRead(authentication.name, #id)")
 * ```
 * The primary `hasPermission(...)` branch already covers persisted skills (and super-admin
 * bypass); this fallback only needs to add the namespace-transitive path for skills that
 * resolve exclusively through the filesystem decorator.
 */
@Component
class SkillAuthorizationService(
    private val skillService: SkillService,
    private val permissionService: PermissionService,
) {
    /**
     * True when [userId] can read the skill identified by [id].
     *
     * - Unknown id (neither persisted nor filesystem-backed): false (fail-closed).
     * - Platform skill (`namespaceId == null`): true — READ is open to any authenticated
     *   caller, mirroring [PermissionService.hasPermission]'s platform-scope rule. Reaching
     *   this method already implies the SpEL's `isAuthenticated()`/prior checks passed.
     * - Namespace skill: delegates to [PermissionService.hasPermission] against the
     *   owning namespace (`EntityType.NAMESPACE`), which grants READ to namespace
     *   MEMBER/ADMIN or via super-admin bypass.
     */
    fun canRead(
        userId: String,
        id: UUID,
    ): Boolean {
        val skill = skillService.findById(id, withRemoved = true) ?: return false
        val namespaceId = skill.namespaceId ?: return true
        return permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
    }
}

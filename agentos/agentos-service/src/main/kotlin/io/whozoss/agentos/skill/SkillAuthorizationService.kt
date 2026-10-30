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
 *
 * [canWrite] and [canDelete] are the mutation counterparts, used so that a write attempt on a
 * filesystem-backed skill reaches [SkillService]'s informative 400 instead of being masked as a
 * 404 by [io.whozoss.agentos.security.declarative.HideOnAccessDenied]. Unlike [canRead], they
 * authorize ONLY filesystem-backed skills, which keeps them strictly non-widening for persisted
 * ones — see [canMutateFilesystemBacked].
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

    /**
     * True when [userId] may update the FILESYSTEM-BACKED skill identified by [id].
     *
     * Used as the fallback branch of `PUT /api/skills/{id}`'s `@PreAuthorize`. Without it, a
     * filesystem-backed skill (which has no Neo4j node, so `hasPermission(#id, 'Skill', 'WRITE')`
     * can never succeed) is rejected by the SpEL and `@HideOnAccessDenied` turns the refusal into
     * a bare 404 — hiding the informative 400 that [SkillService.update] raises for exactly this
     * case ("Filesystem-backed skill '...' is read-only through the API").
     */
    fun canWrite(
        userId: String,
        id: UUID,
    ): Boolean = canMutateFilesystemBacked(userId, id, Action.WRITE)

    /**
     * True when [userId] may delete the FILESYSTEM-BACKED skill identified by [id].
     *
     * The `DELETE /api/skills/{id}` counterpart of [canWrite]; see its documentation.
     */
    fun canDelete(
        userId: String,
        id: UUID,
    ): Boolean = canMutateFilesystemBacked(userId, id, Action.DELETE)

    /**
     * Authorizes [action] on [id] against the OWNING NAMESPACE, but **only** for
     * filesystem-backed skills.
     *
     * The `!skill.isFilesystemBacked -> false` guard is what makes this fallback strictly
     * non-widening, and it is load-bearing: for a persisted skill the fallback can only ever
     * return false, so the effective authorization of persisted skills stays byte-for-byte the
     * graph check performed by [PermissionService.hasPermission] (`BELONGS_TO` transitivity and
     * super-admin bypass included). Dropping that guard would substitute a check on the scalar
     * `namespaceId` property for a check on the graph edge, silently granting writes on a
     * persisted skill whose edge is missing.
     *
     * Requires namespace WRITE/DELETE (not READ), so a plain namespace MEMBER still gets the 404
     * and never learns that a file-backed skill exists; only a namespace ADMIN reaches the 400.
     *
     * A null [Skill.namespaceId] returns false (fail-closed): filesystem discovery is
     * namespace-scoped — [io.whozoss.agentos.skill.FilesystemSkillRepository.findPlatform] is
     * delegate-only — so a platform-level skill is never filesystem-backed and this branch is
     * unreachable in practice.
     */
    private fun canMutateFilesystemBacked(
        userId: String,
        id: UUID,
        action: Action,
    ): Boolean {
        val skill = skillService.findById(id, withRemoved = true) ?: return false
        if (!skill.isFilesystemBacked) return false
        val namespaceId = skill.namespaceId ?: return false
        return permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), action)
    }
}

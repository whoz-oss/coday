package io.whozoss.agentos.namespace

import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import java.util.UUID

/**
 * Business logic for namespace-level ADMIN/MEMBER permission management.
 *
 * Kept separate from [NamespaceService] (entity CRUD) and [NamespacePermissionEndpoints]
 * (HTTP layer) so that permission orchestration has a single, testable home.
 */
interface NamespacePermissionService {
    /**
     * Fully synchronises a single user's namespace roles to match [request].
     *
     * The [SyncUserRolesRequest.namespaceRoles] list is treated as the **complete desired
     * state** for the user:
     * - Namespaces listed with a role are brought to that role (no-op if already correct,
     *   old relation revoked before the new one is granted on a role change).
     * - Namespaces the user currently holds that are **not** listed are fully revoked.
     *
     * Preconditions (caller's responsibility — throw before calling this method):
     * - The user identified by [SyncUserRolesRequest.userExternalId] exists.
     * - Every [NamespaceRoleEntry.namespaceExternalId] resolves to a known namespace.
     *
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if the user or any
     *   listed namespace cannot be resolved.
     */
    fun syncUserRoles(request: SyncUserRolesRequest)

    /**
     * Batch membership update for a single namespace, in **internal userId** terms
     * (no externalId resolution, no auto-creation).
     *
     * Delta semantics (not a declarative replace): entries with a non-null [io.whozoss.agentos.sdk.api.user.UserMembershipRole.role]
     * are added or brought to that role; entries with a null role lose every namespace relation;
     * any user absent from [members] is left untouched.
     *
     * Caller guarantees (enforced by controller-level Bean Validation before this is called):
     * - each entry's role is null, "ADMIN", or "MEMBER"
     * - no duplicate [io.whozoss.agentos.sdk.api.user.UserMembershipRole.userId] values in [members]
     *
     * Two-tier authorization enforced here (the `@PreAuthorize` on the controller only gates
     * entry, it cannot distinguish "add" from "edit/remove"):
     * - Adding a genuinely **new** user (someone holding no current ADMIN/MEMBER relation on
     *   the namespace) requires [callerIsSuperAdmin] — throws
     *   [org.springframework.security.access.AccessDeniedException] (403) otherwise.
     * - Editing the role of, or removing, an already-present user only requires namespace WRITE,
     *   already checked by the controller's `@PreAuthorize`.
     *
     * Only actual role transitions are forwarded to [io.whozoss.agentos.permissions.PermissionService.applyShareBatch]:
     * a member re-sent with its current role is a no-op.
     *
     * Anti-lockout guard: rejects with [io.whozoss.agentos.exception.UnprocessableEntityException] (422)
     * any operation that would leave the namespace with zero ADMIN when it had at least one beforehand.
     *
     * @return the resulting namespace membership, so the caller can refresh without a second round-trip.
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if the namespace does not exist.
     * @throws io.whozoss.agentos.exception.UnprocessableEntityException on an unknown userId,
     *   a revocation targeting a user not currently on the namespace, or the zero-ADMIN lockout guard.
     * @throws org.springframework.security.access.AccessDeniedException if a non-super-admin caller
     *   attempts to add a genuinely new user.
     */
    fun updateMembers(
        namespaceId: UUID,
        members: List<UserMembershipRole>,
        callerIsSuperAdmin: Boolean,
    ): List<NamespaceUserListItem>
}

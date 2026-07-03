package io.whozoss.agentos.namespace

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
}

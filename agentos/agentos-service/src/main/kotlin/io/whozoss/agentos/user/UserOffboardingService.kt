package io.whozoss.agentos.user

import java.util.UUID

/**
 * Namespace-scoped access revocation for a user.
 * Removes only the UserGroup and Namespace relations held within the given namespace —
 * other namespaces the user belongs to are left untouched.
 */
interface UserOffboardingService {
    /**
     * Revokes all UserGroup and Namespace relations for [userId] within [namespaceId].
     * Does not delete the user itself. Idempotent: safe to call on a user with no
     * remaining relations in that namespace.
     *
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if the user does not exist.
     */
    fun revokeNamespaceAccess(userId: UUID, namespaceId: UUID)

    fun revokeNamespaceAccessByExternalId(userExternalId: String, namespaceId: UUID)
}

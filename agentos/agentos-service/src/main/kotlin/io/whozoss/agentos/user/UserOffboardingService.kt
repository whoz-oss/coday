package io.whozoss.agentos.user

import java.util.UUID

/**
 * Platform-wide access revocation for a user, triggered by an external (async)
 * deprovisioning event. Not scoped to a single namespace — removes every
 * UserGroup and Namespace relation the user holds, everywhere.
 */
interface UserOffboardingService {
    /**
     * Revokes all UserGroup and Namespace relations for [userId] across the whole platform.
     * Does not delete the user itself. Idempotent: safe to call on a user with no
     * remaining relations.
     *
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if the user does not exist.
     */
    fun revokeAllAccess(userId: UUID)
}

package io.whozoss.agentos.user

import java.util.UUID

/**
 * Published by [UserServiceImpl.create] once a user has been persisted, whichever way it was created:
 * first access ([UserService.resolveOrCreateByExternalId]), bulk import
 * ([UserService.createByExternalIds]) or the bootstrap admin
 * ([io.whozoss.agentos.bootstrap.BootstrapServiceImpl]).
 *
 * Carries identifiers only, so a listener never holds a stale copy of the user.
 */
data class UserCreatedEvent(
    val userId: UUID,
    val externalId: String,
)

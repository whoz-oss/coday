package io.whozoss.agentos.sdk.api.user

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import io.whozoss.agentos.sdk.api.userGroup.UserGroupSummary
import java.util.UUID

/**
 * HTTP API contract for User entities.
 *
 * Implemented by `UserController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 *
 * Authorization summary (enforced server-side):
 * - [listAll], [create], [delete], [getByIds], [listByExternalIds]: SUPER_ADMIN only
 * - [getById], [update]: SUPER_ADMIN or self (caller's own UUID)
 * - [getMe], [getGroupsByExternalIds]: any authenticated user
 */
interface UserApi : EntityCrudApi<UserDto> {

    /** GET /api/users — list all users. SUPER_ADMIN only. */
    fun listAll(): List<UserDto>

    /** GET /api/users/me — return the current caller's user record. */
    fun getMe(): UserDto

    /**
     * POST /api/users/by-external-ids — look up users by IdP keys. SUPER_ADMIN only.
     *
     * Unknown external IDs are silently omitted. Result order is not guaranteed.
     */
    fun listByExternalIds(externalIds: List<String>): List<UserDto>

    /**
     * POST /api/users/groups-by-external-ids — return groups per user, scoped to a namespace.
     *
     * Returns a map from external ID to the list of groups the user belongs to within the
     * requested namespace. Results are filtered to groups visible to the caller.
     */
    fun getGroupsByExternalIds(request: GroupsByExternalIdsRequest): Map<String, List<UserGroupSummary>>
}

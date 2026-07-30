package io.whozoss.agentos.sdk.api.userGroup

import java.util.UUID

/**
 * HTTP API contract for UserGroup entities.
 *
 * Implemented by `UserGroupController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 *
 * Authorization summary (enforced server-side):
 * - [findByNamespaceId], [getById], [getMembers]: namespace/UserGroup READ
 * - [create]: namespace WRITE
 * - [update]: UserGroup WRITE
 * - [delete]: UserGroup DELETE
 */
interface UserGroupApi {

    /** GET /api/user-groups?namespaceId={uuid} — list all groups in a namespace. */
    fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult>

    fun getById(userGroupId: UUID): UserGroupSearchResult

    /** GET /api/user-groups/{userGroupId}/members — list the group's members with their role. */
    fun getMembers(userGroupId: UUID): List<UserGroupMember>

    fun create(request: UserGroupCreateRequest): UserGroupSearchResult

    /** POST /api/user-groups/{userGroupId} — update a group (delta membership, replace agents). */
    fun update(userGroupId: UUID, request: UserGroupUpdateRequest): UserGroupSearchResult

    fun delete(userGroupId: UUID)
}

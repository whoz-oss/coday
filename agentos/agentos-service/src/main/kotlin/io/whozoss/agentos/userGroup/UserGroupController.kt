package io.whozoss.agentos.userGroup

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.sdk.api.common.ListMetadata
import io.whozoss.agentos.sdk.api.userGroup.UserGroupApi
import io.whozoss.agentos.sdk.api.userGroup.UserGroupMember
import io.whozoss.agentos.sdk.api.userGroup.UserGroupMembersListRequest
import io.whozoss.agentos.sdk.api.userGroup.UserGroupMembersListResponse
import io.whozoss.agentos.sdk.api.userGroup.UserGroupSearchResult
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for UserGroup entities. Implements [UserGroupApi] so external consumers
 * can declare a Feign client against the SDK interface.
 */
@RestController
@RequestMapping("/api/user-groups", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserGroupController(
    private val userGroupService: UserGroupService,
) : UserGroupApi {
    @GetMapping
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    override fun findByNamespaceId(
        @RequestParam namespaceId: UUID,
    ): List<UserGroupSearchResult> = userGroupService.findByNamespaceId(namespaceId).map { it.toDto() }

    @GetMapping("/{userGroupId}")
    @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable userGroupId: UUID,
    ): UserGroupSearchResult =
        userGroupService.findByIdWithDetails(userGroupId)?.toDto()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @GetMapping("/{userGroupId}/members")
    @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'READ')")
    @HideOnAccessDenied
    override fun getMembers(
        @PathVariable userGroupId: UUID,
    ): List<UserGroupMember> = userGroupService.getMembers(userGroupId).map { it.toDto() }

    // Not on UserGroupApi: the paged variant stays out of the shared Feign contract.
    @PostMapping("/{userGroupId}/members/list", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'READ')")
    @HideOnAccessDenied
    @Operation(summary = "List the members of a group, one cursor-paged page at a time")
    fun listMembers(
        @PathVariable userGroupId: UUID,
        @Valid @RequestBody request: UserGroupMembersListRequest,
    ): UserGroupMembersListResponse =
        userGroupService.getMembersPage(userGroupId, request.limit, request.next).let { page ->
            UserGroupMembersListResponse(
                data = page.items.map { it.toDto() },
                metadata = ListMetadata(next = page.next, totalCount = page.totalCount),
            )
        }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#request.namespaceId, 'Namespace', 'WRITE')")
    override fun create(
        @Valid @RequestBody request: io.whozoss.agentos.sdk.api.userGroup.UserGroupCreateRequest,
    ): UserGroupSearchResult = userGroupService.createFromRequest(request).toDto()

    @PostMapping("/{userGroupId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'WRITE')")
    override fun update(
        @PathVariable userGroupId: UUID,
        @Valid @RequestBody request: io.whozoss.agentos.sdk.api.userGroup.UserGroupUpdateRequest,
    ): UserGroupSearchResult =
        userGroupService
            .updateFromRequest(
                userGroupId,
                UserGroupUpdateRequest(
                    name = request.name,
                    userExternalIdsToAdd = request.userExternalIdsToAdd,
                    userExternalIdsToRemove = request.userExternalIdsToRemove,
                    adminExternalIds = request.adminExternalIds,
                    adminExternalIdsToAdd = request.adminExternalIdsToAdd,
                    adminExternalIdsToRemove = request.adminExternalIdsToRemove,
                    agentIds = request.agentIds,
                ),
            ).toDto()

    @DeleteMapping("/{userGroupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'DELETE')")
    override fun delete(
        @PathVariable userGroupId: UUID,
    ) {
        val deleted = userGroupService.delete(userGroupId)
        if (!deleted) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
}

// ---------------------------------------------------------------------------
// Extension: service-internal UserGroupSearchResult → SDK UserGroupSearchResult
// ---------------------------------------------------------------------------

private fun io.whozoss.agentos.userGroup.UserGroupSearchResult.toDto() =
    UserGroupSearchResult(
        userGroupId = userGroupId,
        namespaceId = namespaceId,
        namespaceExternalId = namespaceExternalId,
        name = name,
        agentIds = agentIds,
        userCount = userCount,
    )

// ---------------------------------------------------------------------------
// Extension: service-internal UserGroupMember → SDK UserGroupMember
// ---------------------------------------------------------------------------

private fun io.whozoss.agentos.userGroup.UserGroupMember.toDto() =
    UserGroupMember(
        userId = userId,
        externalId = externalId,
        role = role,
        email = email,
        firstname = firstname,
        lastname = lastname,
    )

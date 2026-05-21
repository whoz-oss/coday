package io.whozoss.agentos.userGroup

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

@RestController
@RequestMapping("/api/user-groups", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserGroupController(
    private val userGroupService: UserGroupService,
) {
    @GetMapping
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun searchByNamespaceId(
        @RequestParam namespaceId: UUID,
    ): List<UserGroupSearchResultResource> =
        userGroupService
            .findByNamespaceId(namespaceId)
            .map { it.toResource() }

    @GetMapping("/{userGroupId}")
    @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'READ')")
    @HideOnAccessDenied
    fun getById(
        @PathVariable userGroupId: UUID,
    ): UserGroupSearchResultResource =
        userGroupService.findByIdWithDetails(userGroupId)?.toResource()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#request.namespaceId, 'Namespace', 'WRITE')")
    fun create(
        @Valid @RequestBody request: UserGroupCreateRequest,
    ): UserGroupSearchResultResource =
        userGroupService.createFromRequest(request).toResource()

    @PostMapping("/{userGroupId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'WRITE')")
    fun update(
        @PathVariable userGroupId: UUID,
        @Valid @RequestBody request: UserGroupUpdateRequest,
    ): UserGroupSearchResultResource =
        userGroupService.updateFromRequest(userGroupId, request).toResource()

    @DeleteMapping("/{userGroupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#userGroupId, 'UserGroup', 'DELETE')")
    fun delete(
        @PathVariable userGroupId: UUID,
    ) {
        val deleted = userGroupService.delete(userGroupId)
        if (!deleted) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    private fun UserGroupSearchResult.toResource() =
        UserGroupSearchResultResource(
            userGroupId = userGroupId,
            namespaceId = namespaceId,
            namespaceExternalId = namespaceExternalId,
            name = name,
            agentIds = agentIds,
            userCount = userCount,
        )
}

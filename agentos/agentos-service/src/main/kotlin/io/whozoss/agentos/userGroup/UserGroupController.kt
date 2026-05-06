package io.whozoss.agentos.userGroup

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
    fun searchByNamespaceExternalId(
        @RequestParam namespaceExternalId: String,
    ): List<UserGroupSearchResultResource> =
        userGroupService
            .findByNamespaceExternalId(namespaceExternalId)
            .map { it.toResource() }

    @GetMapping("/{userGroupId}")
    fun getById(
        @PathVariable userGroupId: UUID,
    ): UserGroupSearchResultResource =
        userGroupService.findByIdWithDetails(userGroupId)?.toResource()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: UserGroupCreateRequest,
    ): UserGroupSearchResultResource =
        userGroupService.createFromRequest(request).toResource()

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

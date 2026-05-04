package io.whozoss.agentos.userGroup

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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

    private fun UserGroupSearchResult.toResource() =
        UserGroupSearchResultResource(
            userGroupId = userGroupId,
            namespaceId = namespaceId,
            namespaceExternalId = namespaceExternalId,
            name = name,
        )
}

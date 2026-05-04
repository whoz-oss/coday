package io.whozoss.agentos.usergroup

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping(
    "/api/user-groups",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserGroupController(
    private val userGroupService: UserGroupService,
) {
    @PostMapping("/list", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.OK)
    fun list(
        @Valid @RequestBody request: UserGroupListRequest,
    ): UserGroupListResponse {
        logger.info { "listing user groups for namespace ${request.namespaceId}" }
        val groups = userGroupService.list(request.namespaceId)
        return UserGroupListResponse(
            data = groups.map { toResponse(it) },
        )
    }

    @GetMapping("/{userGroupId}")
    @ResponseStatus(HttpStatus.OK)
    fun get(
        @PathVariable @NotNull userGroupId: UUID,
        @RequestParam @NotNull namespaceId: UUID,
    ): UserGroupResponse {
        logger.info { "getting user group $userGroupId" }
        return toResponse(userGroupService.get(userGroupId))
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: UserGroupCreateRequest,
    ): UserGroupResponse {
        logger.info { "creating user group '${request.name}' in namespace ${request.namespaceId}" }
        return toResponse(userGroupService.create(request))
    }

    @PostMapping("/{userGroupId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.OK)
    fun update(
        @PathVariable @NotNull userGroupId: UUID,
        @Valid @RequestBody request: UserGroupUpdateRequest,
    ): UserGroupResponse {
        logger.info { "updating user group $userGroupId" }
        return toResponse(userGroupService.update(userGroupId, request))
    }

    @DeleteMapping("/{userGroupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable @NotNull userGroupId: UUID,
        @RequestParam @NotNull namespaceId: UUID,
    ) {
        logger.info { "deleting user group $userGroupId" }
        userGroupService.delete(userGroupId)
    }

    private fun toResponse(userGroup: UserGroup): UserGroupResponse =
        UserGroupResponse(
            userGroupId = userGroup.id,
            namespaceId = userGroup.namespaceId,
            name = userGroup.name,
            agentIds = userGroupService.getAgentIds(userGroup.id),
            userCount = userGroupService.countUsers(userGroup.id),
        )

    companion object : KLogging()
}

package io.whozoss.agentos.usergroup

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@EnableConfigurationProperties(UserGroupConfigProperties::class)
class UserGroupServiceImpl(
    private val userGroupRepository: UserGroupRepository,
    private val userService: UserService,
    private val config: UserGroupConfigProperties,
) : UserGroupService {
    override fun list(namespaceId: UUID): List<UserGroup> =
        userGroupRepository.findByParent(namespaceId)

    override fun get(userGroupId: UUID): UserGroup =
        userGroupRepository.findByIds(listOf(userGroupId)).firstOrNull()
            ?: throw ResourceNotFoundException("UserGroup not found: $userGroupId")

    override fun getAgentIds(userGroupId: UUID): List<UUID> =
        userGroupRepository.findAgentIds(userGroupId)

    override fun countUsers(userGroupId: UUID): Int =
        userGroupRepository.countUsers(userGroupId)

    override fun create(request: UserGroupCreateRequest): UserGroup {
        val resolvedExternalUserIds = request.userExternalIds.map { externalId ->
            userService.resolveOrCreateByExternalId(externalId).id
        }.toSet()
        val allUserIds = request.userIds + resolvedExternalUserIds
        if (allUserIds.size > config.maxUsersPerGroup) {
            throw UserGroupLimitExceededException(
                "Cannot add ${allUserIds.size} users: max is ${config.maxUsersPerGroup}"
            )
        }
        if (request.agentIds.size > config.maxAgentsPerGroup) {
            throw UserGroupLimitExceededException(
                "Cannot add ${request.agentIds.size} agents: max is ${config.maxAgentsPerGroup}"
            )
        }
        val userGroup = UserGroup(
            metadata = EntityMetadata(),
            namespaceId = request.namespaceId,
            name = request.name,
        )
        val saved = userGroupRepository.save(userGroup)
        allUserIds.forEach { userId ->
            userGroupRepository.addUser(saved.id, userId)
        }
        if (request.agentIds.isNotEmpty()) {
            userGroupRepository.replaceAgents(saved.id, request.agentIds)
        }
        return saved
    }

    override fun update(userGroupId: UUID, request: UserGroupUpdateRequest): UserGroup {
        val existing = get(userGroupId)
        val resolvedAddedExternalUserIds = request.addUserExternalIds.map { externalId ->
            userService.resolveOrCreateByExternalId(externalId).id
        }.toSet()
        val resolvedRemovedExternalUserIds = request.removeUserExternalIds.mapNotNull { externalId ->
            userService.findByExternalId(externalId)?.id
        }.toSet()
        val allAddedUserIds = request.addUserIds + resolvedAddedExternalUserIds
        val allRemovedUserIds = request.removeUserIds + resolvedRemovedExternalUserIds
        val currentUserCount = userGroupRepository.countUsers(userGroupId)
        val projectedUserCount = currentUserCount + allAddedUserIds.size - allRemovedUserIds.size
        if (projectedUserCount > config.maxUsersPerGroup) {
            throw UserGroupLimitExceededException(
                "Cannot exceed ${config.maxUsersPerGroup} users per group (would be $projectedUserCount)"
            )
        }
        if (request.agentIds.size > config.maxAgentsPerGroup) {
            throw UserGroupLimitExceededException(
                "Cannot exceed ${config.maxAgentsPerGroup} agents per group (got ${request.agentIds.size})"
            )
        }
        allAddedUserIds.forEach { userId ->
            userGroupRepository.addUser(userGroupId, userId)
        }
        allRemovedUserIds.forEach { userId ->
            userGroupRepository.removeUser(userGroupId, userId)
        }
        userGroupRepository.replaceAgents(userGroupId, request.agentIds)
        val updated = existing.copy(
            name = request.name,
        )
        return userGroupRepository.save(updated)
    }

    override fun delete(userGroupId: UUID) {
        userGroupRepository.softDeleteWithRelationships(userGroupId)
    }

}

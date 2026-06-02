package io.whozoss.agentos.userGroup

import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserGroupServiceImpl(
    private val userGroupRepository: UserGroupRepository,
    private val namespaceService: NamespaceService,
    private val agentConfigRepository: AgentConfigRepository,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : UserGroupService {
    @Transactional
    override fun create(entity: UserGroup): UserGroup =
        try {
            userGroupRepository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("A user group with name '${entity.name}' already exists in this namespace", e)
        }

    @Transactional
    override fun update(entity: UserGroup): UserGroup =
        try {
            userGroupRepository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("A user group with name '${entity.name}' already exists in this namespace", e)
        }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<UserGroup> = userGroupRepository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<UserGroup> = userGroupRepository.findByParent(parentId)

    @Transactional
    override fun delete(id: UUID): Boolean = userGroupRepository.delete(id)

    @Transactional
    override fun deleteByParent(parentId: UUID): Int = userGroupRepository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult> =
        userGroupRepository.findByNamespaceId(namespaceId)

    override fun findByIdWithDetails(id: UUID): UserGroupSearchResult? = userGroupRepository.findByIdWithDetails(id)

    @Transactional
    override fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult {
        val namespace =
            namespaceService.getById(request.namespaceId)

        validateAgentsInNamespace(request.agentIds, namespace.id)

        val group =
            create(
                UserGroup(
                    namespaceId = namespace.id,
                    name = request.name,
                ),
            )

        if (request.agentIds.isNotEmpty()) {
            userGroupRepository.addAgents(group.id, request.agentIds)
        }

        if (request.userExternalIdsToAdd.isNotEmpty()) {
            val existingIds = userService.findByExternalIds(request.userExternalIdsToAdd).map { it.externalId }.toSet()
            val missingIds = request.userExternalIdsToAdd - existingIds
            missingIds.forEach { userService.resolveOrCreateByExternalId(it) }
            userGroupRepository.addUsers(group.id, request.userExternalIdsToAdd)
        }

        return userGroupRepository.findByIdWithDetails(group.id)
            ?: throw IllegalStateException("UserGroup ${group.id} not found after creation")
    }

    @Transactional
    override fun updateFromRequest(
        userGroupId: UUID,
        request: UserGroupUpdateRequest,
    ): UserGroupSearchResult {
        val intersection = request.userExternalIdsToAdd.toSet() intersect request.userExternalIdsToRemove.toSet()
        if (intersection.isNotEmpty()) {
            throw UnprocessableEntityException(
                "User external IDs cannot appear in both addedUserExternalIds and removedUserExternalIds: $intersection",
            )
        }

        val existing = getById(userGroupId)

        validateAgentsInNamespace(request.agentIds, existing.namespaceId)

        update(existing.copy(name = request.name))

        userGroupRepository.removeAllAgents(userGroupId)
        if (request.agentIds.isNotEmpty()) {
            userGroupRepository.addAgents(userGroupId, request.agentIds)
        }

        if (request.userExternalIdsToAdd.isNotEmpty()) {
            val existingIds = userService.findByExternalIds(request.userExternalIdsToAdd).map { it.externalId }.toSet()
            val missingIds = request.userExternalIdsToAdd - existingIds
            missingIds.forEach { userService.resolveOrCreateByExternalId(it) }
            userGroupRepository.addUsers(userGroupId, request.userExternalIdsToAdd)
        }

        if (request.userExternalIdsToRemove.isNotEmpty()) {
            userGroupRepository.removeUsers(userGroupId, request.userExternalIdsToRemove)
        }

        return userGroupRepository.findByIdWithDetails(userGroupId)
            ?: throw IllegalStateException("UserGroup $userGroupId not found after update")
    }

    override fun findGroupsByUserExternalIdsVisibleToUser(
        externalIds: Collection<String>,
        user: User,
    ): Map<String, List<UserGroupSummary>> {
        val allGroups = userGroupRepository.findGroupsByUserExternalIds(externalIds)
        val visibleGroupIds = if (user.isAdmin) {
            allGroups.values.flatten().map { it.id.toString() }.toSet()
        } else {
            permissionService.filterVisibleIds(
                userId = user.id.toString(),
                entityType = EntityType.USER_GROUP,
                ids = allGroups.values.flatten().map { it.id.toString() }.toSet(),
                action = Action.READ,
            )
        }
        return allGroups
            .mapValues { (_, groups) -> groups.filter { it.id.toString() in visibleGroupIds } }
            .filterValues { it.isNotEmpty() }
    }

    private fun validateAgentsInNamespace(
        agentIds: Set<UUID>,
        namespaceId: UUID,
    ) {
        agentIds
            .takeIf { it.isNotEmpty() }
            ?.let { nonEmptyAgentIds ->
                val found = agentConfigRepository.findByIds(nonEmptyAgentIds)
                val validIds = found.filter { it.namespaceId == namespaceId }.map { it.id }.toSet()
                val invalidIds = agentIds - validIds
                if (invalidIds.isNotEmpty()) {
                    throw UnprocessableEntityException("Agent configs not found in namespace: $invalidIds")
                }
            }
    }
}

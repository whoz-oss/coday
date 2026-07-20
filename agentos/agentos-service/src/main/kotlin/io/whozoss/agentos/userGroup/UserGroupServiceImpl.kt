package io.whozoss.agentos.userGroup

import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.userGroup.UserGroupCreateRequest
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<UserGroup> = userGroupRepository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<UserGroup> = userGroupRepository.findByParent(parentId)

    @Transactional
    override fun delete(id: UUID): Boolean = userGroupRepository.delete(id)

    @Transactional
    override fun deleteByParent(parentId: UUID): Int = userGroupRepository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult> = userGroupRepository.findByNamespaceId(namespaceId)

    override fun findByIdWithDetails(id: UUID): UserGroupSearchResult? = userGroupRepository.findByIdWithDetails(id)

    @Transactional(readOnly = true)
    override fun getMembers(userGroupId: UUID): List<UserGroupMember> = userGroupRepository.findMembers(userGroupId)

    @Transactional
    override fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult {
        val namespace =
            namespaceService.getById(request.namespaceId)

        validateAgentsInNamespace(request.agentIds, namespace.id)
        validateAdminsAreMembers(request.adminExternalIds, request.userExternalIdsToAdd)

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
            val existingUsers = userService.findByExternalIds(request.userExternalIdsToAdd)
            val missingIds = request.userExternalIdsToAdd - existingUsers.map { it.externalId }.toSet()
            val createdUsers = userService.createByExternalIds(missingIds)
            userGroupRepository.addUsers(group.id, request.userExternalIdsToAdd)
            reconcileRoles(
                userGroupId = group.id,
                currentMembers = emptyList(),
                addedUsers = existingUsers + createdUsers,
                adminExternalIds = request.adminExternalIds,
                removedExternalIds = emptySet(),
            )
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

        val adminRemoveConflict = request.adminExternalIds intersect request.userExternalIdsToRemove
        if (adminRemoveConflict.isNotEmpty()) {
            throw UnprocessableEntityException(
                "User external IDs cannot appear in both adminExternalIds and removedUserExternalIds: $adminRemoveConflict",
            )
        }

        val existing = getById(userGroupId)

        validateAgentsInNamespace(request.agentIds, existing.namespaceId)

        // Admins must be members after the update: existing members, plus the added, minus the removed.
        val currentMembers = userGroupRepository.findMembers(userGroupId)
        val resultingMembers =
            (currentMembers.map { it.externalId }.toSet() - request.userExternalIdsToRemove) +
                request.userExternalIdsToAdd
        validateAdminsAreMembers(request.adminExternalIds, resultingMembers)

        update(existing.copy(name = request.name))

        userGroupRepository.removeAllAgents(userGroupId)
        if (request.agentIds.isNotEmpty()) {
            userGroupRepository.addAgents(userGroupId, request.agentIds)
        }

        var addedUsers = emptyList<User>()
        if (request.userExternalIdsToAdd.isNotEmpty()) {
            val existingUsers = userService.findByExternalIds(request.userExternalIdsToAdd)
            val missingIds = request.userExternalIdsToAdd - existingUsers.map { it.externalId }.toSet()
            addedUsers = existingUsers + userService.createByExternalIds(missingIds)
            userGroupRepository.addUsers(userGroupId, request.userExternalIdsToAdd)
        }

        if (request.userExternalIdsToRemove.isNotEmpty()) {
            userGroupRepository.removeUsers(userGroupId, request.userExternalIdsToRemove)
            // removeUsers deletes the [:MEMBER|ADMIN] edges directly, bypassing the permission
            // cache. A removed group ADMIN would otherwise keep a cached WRITE/DELETE grant on
            // the group until the TTL expires (and could re-add themselves as admin). Invalidate
            // each removed member; the promote/demote path already clears the cache via applyShareBatch.
            currentMembers
                .filter { it.externalId in request.userExternalIdsToRemove }
                .forEach { permissionService.clearUserCache(it.userId.toString()) }
        }

        reconcileRoles(
            userGroupId = userGroupId,
            currentMembers = currentMembers,
            addedUsers = addedUsers,
            adminExternalIds = request.adminExternalIds,
            removedExternalIds = request.userExternalIdsToRemove,
        )

        return userGroupRepository.findByIdWithDetails(userGroupId)
            ?: throw IllegalStateException("UserGroup $userGroupId not found after update")
    }

    override fun findGroupsByUserExternalIdsVisibleToUser(
        externalIds: Collection<String>,
        user: User,
        namespaceId: UUID?,
    ): Map<String, List<UserGroupSummary>> {
        val allGroups = userGroupRepository.findGroupsByUserExternalIds(externalIds, namespaceId)
        val visibleGroupIds =
            if (user.isAdmin) {
                allGroups.values
                    .flatten()
                    .map { it.id.toString() }
                    .toSet()
            } else {
                permissionService.filterVisibleIds(
                    userId = user.id.toString(),
                    entityType = EntityType.USER_GROUP,
                    ids =
                        allGroups.values
                            .flatten()
                            .map { it.id.toString() }
                            .toSet(),
                    action = Action.READ,
                )
            }
        return allGroups
            .mapValues { (_, groups) -> groups.filter { it.id.toString() in visibleGroupIds } }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Reconciles member roles as a delta batch through the permission layer: promotes target
     * admins that are not already ADMIN, demotes current ADMINs that left [adminExternalIds]
     * (unless they leave the group via [removedExternalIds]). Members whose role does not
     * change are not sent, so the batch only pays for actual role transitions.
     *
     * [currentMembers] is the membership BEFORE the update (with roles and internal user ids);
     * [addedUsers] maps the external ids added by this request to their internal user ids.
     */
    private fun reconcileRoles(
        userGroupId: UUID,
        currentMembers: List<UserGroupMember>,
        addedUsers: List<User>,
        adminExternalIds: Set<String>,
        removedExternalIds: Set<String>,
    ) {
        val roleByExternalId = currentMembers.associateBy({ it.externalId }, { it.role })
        val userIdByExternalId =
            currentMembers.associateBy({ it.externalId }, { it.userId.toString() }) +
                addedUsers.associateBy({ it.externalId }, { it.id.toString() })
        val promotions =
            adminExternalIds
                .filter { roleByExternalId[it] != PermissionRelation.ADMIN.name }
                .mapNotNull { userIdByExternalId[it] }
                .map { it to PermissionRelation.ADMIN }
        val demotions =
            currentMembers
                .filter {
                    it.role == PermissionRelation.ADMIN.name &&
                        it.externalId !in adminExternalIds &&
                        it.externalId !in removedExternalIds
                }.map { it.userId.toString() to PermissionRelation.MEMBER }
        val entries: List<Pair<String, PermissionRelation?>> = promotions + demotions
        if (entries.isNotEmpty()) {
            permissionService.applyShareBatch(EntityType.USER_GROUP, userGroupId.toString(), entries)
        }
    }

    private fun validateAdminsAreMembers(
        adminExternalIds: Set<String>,
        memberExternalIds: Set<String>,
    ) {
        val notMembers = adminExternalIds - memberExternalIds
        if (notMembers.isNotEmpty()) {
            throw UnprocessableEntityException("Admin external IDs must also be members: $notMembers")
        }
    }

    private fun validateAgentsInNamespace(
        agentIds: Set<UUID>,
        namespaceId: UUID,
    ) {
        agentIds
            .takeIf { it.isNotEmpty() }
            ?.let { nonEmptyAgentIds ->
                val found = agentConfigRepository.findByIds(nonEmptyAgentIds)
                // An agent is valid if it belongs to the target namespace OR is a platform agent
                // (namespaceId = null), which can be added to any group in any namespace.
                val validIds =
                    found
                        .filter { it.namespaceId == null || it.namespaceId == namespaceId }
                        .map { it.id }
                        .toSet()
                val invalidIds = agentIds - validIds
                if (invalidIds.isNotEmpty()) {
                    throw UnprocessableEntityException("Agent configs not found in namespace: $invalidIds")
                }
            }
    }
}

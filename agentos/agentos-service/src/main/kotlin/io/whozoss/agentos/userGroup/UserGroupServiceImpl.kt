package io.whozoss.agentos.userGroup

import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
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
) : UserGroupService {
    override fun create(entity: UserGroup): UserGroup =
        try {
            userGroupRepository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("A user group with name '${entity.name}' already exists in this namespace", e)
        }

    override fun update(entity: UserGroup): UserGroup =
        try {
            userGroupRepository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException("A user group with name '${entity.name}' already exists in this namespace", e)
        }

    override fun findByIds(ids: Collection<UUID>): List<UserGroup> = userGroupRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<UserGroup> = userGroupRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = userGroupRepository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = userGroupRepository.deleteByParent(parentId)

    override fun findByNamespaceExternalId(externalId: String): List<UserGroupSearchResult> =
        userGroupRepository.findByNamespaceExternalId(externalId)

    override fun findByIdWithDetails(id: UUID): UserGroupSearchResult? = userGroupRepository.findByIdWithDetails(id)

    @Transactional
    override fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult {
        val namespace =
            namespaceService.findByExternalId(request.namespaceExternalId)
                ?: throw UnprocessableEntityException("Namespace not found for externalId: ${request.namespaceExternalId}")

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

        if (request.userExternalIds.isNotEmpty()) {
            request.userExternalIds.forEach { userService.resolveOrCreateByExternalId(it) }
            userGroupRepository.addUsers(group.id, request.userExternalIds)
        }

        return userGroupRepository.findByIdWithDetails(group.id)
            ?: throw IllegalStateException("UserGroup ${group.id} not found after creation")
    }

    @Transactional
    override fun updateFromRequest(
        userGroupId: UUID,
        request: UserGroupUpdateRequest,
    ): UserGroupSearchResult {
        val intersection = request.addedUserExternalIds.toSet() intersect request.removedUserExternalIds.toSet()
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

        if (request.addedUserExternalIds.isNotEmpty()) {
            request.addedUserExternalIds.forEach { userService.resolveOrCreateByExternalId(it) }
            userGroupRepository.addUsers(userGroupId, request.addedUserExternalIds)
        }

        if (request.removedUserExternalIds.isNotEmpty()) {
            userGroupRepository.removeUsers(userGroupId, request.removedUserExternalIds)
        }

        return userGroupRepository.findByIdWithDetails(userGroupId)
            ?: throw IllegalStateException("UserGroup $userGroupId not found after update")
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

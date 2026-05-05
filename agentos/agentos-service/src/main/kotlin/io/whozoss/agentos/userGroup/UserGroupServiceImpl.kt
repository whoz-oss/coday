package io.whozoss.agentos.userGroup

import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.*

@Service
class UserGroupServiceImpl(
    private val userGroupRepository: UserGroupRepository,
    private val namespaceService: NamespaceService,
    private val agentConfigRepository: AgentConfigRepository,
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

        userGroupRepository.removeAllAgents(group.id)
        if (request.agentIds.isNotEmpty()) {
            userGroupRepository.addAgents(group.id, request.agentIds)
        }

        // TODO: Query user group with counters
        return userGroupRepository
            .findByNamespaceExternalId(request.namespaceExternalId)
            .first { it.name == request.name }
    }

    private fun validateAgentsInNamespace(
        agentIds: List<UUID>,
        namespaceId: UUID,
    ) {
        if (agentIds.isEmpty()) return
        val found = agentConfigRepository.findByIds(agentIds)
        val validIds = found.filter { it.namespaceId == namespaceId }.map { it.id }.toSet()
        val invalidIds = agentIds - validIds
        if (invalidIds.isNotEmpty()) {
            throw UnprocessableEntityException("Agent configs not found in namespace: $invalidIds")
        }
    }
}

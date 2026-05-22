package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.user.UserService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Delegates all persistence operations to [AgentConfigRepository].
 */
@Service
class AgentConfigServiceImpl(
    private val agentConfigRepository: AgentConfigRepository,
    private val userService: UserService,
) : AgentConfigService {
    override fun create(entity: AgentConfig): AgentConfig = agentConfigRepository.save(entity)

    override fun update(entity: AgentConfig): AgentConfig = agentConfigRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<AgentConfig> = agentConfigRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<AgentConfig> = agentConfigRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = agentConfigRepository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = agentConfigRepository.deleteByParent(parentId)

    override fun findByName(
        namespaceId: UUID,
        name: String,
    ): AgentConfig? =
        agentConfigRepository
            .findByParent(namespaceId)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }

    override fun findAvailableByUserExternalId(namespaceId: UUID, userExternalId: String): List<AgentConfig> {
        val user = userService.findByExternalId(userExternalId) ?: return emptyList()
        return agentConfigRepository.findAvailableByNamespaceIdAndUserId(namespaceId = namespaceId, userId = user.id)
    }

    override fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String?): List<AgentConfig> =
        agentConfigRepository.findAvailableByNamespaceIdAndUserId(namespaceId = namespaceId, userId = userId, agentName = agentName)
}

package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.user.UserService
import mu.KLogging
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
    override fun create(entity: AgentConfig): AgentConfig =
        agentConfigRepository.save(entity)

    override fun update(entity: AgentConfig): AgentConfig =
        agentConfigRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<AgentConfig> = agentConfigRepository.findByIds(ids, withRemoved)

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
        val user = userService.findByExternalId(userExternalId)
            ?: run {
                logger.warn { "[AgentConfigService] User not found for externalId: $userExternalId" }
                throw ResourceNotFoundException("User not found for externalId: $userExternalId")
            }
        // Admins bypass DEPLOYED_TO filtering — they can use any agent in the namespace.
        return when {
            user.isAdmin -> agentConfigRepository.findByParent(namespaceId)
            else -> agentConfigRepository.findAvailableByNamespaceIdAndUserId(namespaceId = namespaceId, userId = user.id, agentName = null)
        }
    }

    companion object : KLogging()

    override fun findAvailableByNamespaceIdAndUserId(namespaceId: UUID, userId: UUID, agentName: String?): List<AgentConfig> {
        val user = userService.findById(userId)
        // Admins bypass DEPLOYED_TO filtering — they can use any agent in the namespace.
        return when {
            user?.isAdmin == true -> agentConfigRepository
                .findByParent(namespaceId)
                .filter { agentName == null || it.name.equals(agentName, ignoreCase = true) }
            else -> agentConfigRepository.findAvailableByNamespaceIdAndUserId(namespaceId = namespaceId, userId = userId, agentName = agentName)
        }
    }
}

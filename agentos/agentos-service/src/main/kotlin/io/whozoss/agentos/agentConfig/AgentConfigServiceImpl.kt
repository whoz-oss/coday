package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.DeploymentScope
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
    override fun create(entity: AgentConfig): AgentConfig {
        requireUniqueName(entity.namespaceId, entity.name, excludeId = null)
        return agentConfigRepository.save(entity)
    }

    override fun update(entity: AgentConfig): AgentConfig {
        requireUniqueName(entity.namespaceId, entity.name, excludeId = entity.metadata.id)
        return agentConfigRepository.save(entity)
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<AgentConfig> = agentConfigRepository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID?): List<AgentConfig> = agentConfigRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = agentConfigRepository.delete(id)

    override fun deleteByParent(parentId: UUID?): Int = agentConfigRepository.deleteByParent(parentId)

    override fun findByName(
        namespaceId: UUID?,
        name: String,
    ): AgentConfig? =
        agentConfigRepository
            .findByParent(namespaceId)
            .firstOrNull { it.name.equals(name, ignoreCase = true) } ?: namespaceId?.let {
            agentConfigRepository.findByParent(null).firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

    override fun findAvailableByUserExternalId(
        namespaceId: UUID,
        userExternalId: String,
    ): List<AgentConfig> {
        val user =
            userService.findByExternalId(userExternalId)
                ?: run {
                    logger.warn { "[AgentConfigService] User not found for externalId: $userExternalId" }
                    throw ResourceNotFoundException("User not found for externalId: $userExternalId")
                }
        return findDeployedByNamespaceIdAndUserIdAndName(
            namespaceId = namespaceId,
            userId = user.id,
            agentName = null,
        )
    }

    override fun findDeployedByNamespaceIdAndUserIdAndName(
        namespaceId: UUID?,
        userId: UUID?,
        agentName: String?,
    ): List<AgentConfig> =
        agentConfigRepository.findDeployedByNamespaceIdAndUserIdAndName(
            namespaceId = namespaceId,
            userId = userId,
            agentName = agentName,
        )

    override fun findByNamespace(
        namespaceId: UUID?,
        withDisabled: Boolean,
    ): List<AgentConfig> = agentConfigRepository.findByParent(namespaceId, withDisabled)

    override fun enable(id: UUID): AgentConfig {
        val existing =
            agentConfigRepository.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        return agentConfigRepository.save(existing.copy(enabled = true))
    }

    override fun disable(id: UUID): AgentConfig {
        val existing =
            agentConfigRepository.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        return agentConfigRepository.save(existing.copy(enabled = false))
    }

    override fun findDeployments(id: UUID): List<DeploymentScope> =
        agentConfigRepository.findDeployments(id)

    /**
     * Throws [IllegalArgumentException] if an active [AgentConfig] with the same [name]
     * (case-insensitive) already exists at the same scope ([namespaceId]).
     *
     * On update, pass [excludeId] = the entity being updated so it does not conflict
     * with itself.
     */
    private fun requireUniqueName(
        namespaceId: UUID?,
        name: String,
        excludeId: UUID?,
    ) {
        val conflict =
            agentConfigRepository
                .findByParent(namespaceId)
                .firstOrNull { it.name.equals(name, ignoreCase = true) && it.metadata.id != excludeId }
        if (conflict != null) {
            val scope = namespaceId?.toString() ?: "platform"
            throw IllegalArgumentException(
                "An AgentConfig named '$name' already exists at scope '$scope' (id=${conflict.metadata.id}).",
            )
        }
    }

    companion object : KLogging()
}

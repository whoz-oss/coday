package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.scheduledPrompt.ScheduledPromptRepository
import io.whozoss.agentos.scheduledPrompt.actAgentName
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Delegates all persistence operations to [AgentConfigRepository].
 */
@Service
class AgentConfigServiceImpl(
    private val agentConfigRepository: AgentConfigRepository,
    private val promptRepository: PromptRepository,
    private val scheduledPromptRepository: ScheduledPromptRepository,
    private val userService: UserService,
) : AgentConfigService {
    override fun create(entity: AgentConfig): AgentConfig {
        requireUniqueName(entity.namespaceId, entity.name, excludeId = null)
        return agentConfigRepository.save(entity)}

    override fun update(entity: AgentConfig): AgentConfig {
        requireUniqueName(entity.namespaceId, entity.name, excludeId = entity.metadata.id)
        return agentConfigRepository.save(entity)}

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<AgentConfig> = agentConfigRepository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID?): List<AgentConfig> = agentConfigRepository.findByParent(parentId)

    @Transactional
    override fun delete(id: UUID): Boolean {
        val deleted = agentConfigRepository.delete(id)
        if (deleted) {
            val deletedPromptsCount = promptRepository.softDeleteByAgentConfigId(id)
            if (deletedPromptsCount > 0) {
                logger.info { "[AgentConfigService] Soft-deleted $deletedPromptsCount prompt(s) for deleted agentConfigId=$id" }
            }
            val deletedScheduledPromptsCount = scheduledPromptRepository.softDeleteWithPromptsByAgentConfigId(id)
            if (deletedScheduledPromptsCount > 0) {
                logger.info { "[AgentConfigService] Soft-deleted $deletedScheduledPromptsCount scheduled prompt(s) and their linked prompts for agentConfigId=$id" }
            }
        }
        return deleted
    }

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

    /**
     * Disables the agent, and the scheduled prompts that depend on it, in the same transaction:
     * - those that run it (`agentConfigId`);
     * - those of the same namespace whose loop payload targets it by name (`flowConfiguration.act.agentName`,
     *   case-insensitive), which would otherwise fail or do nothing at run time.
     *
     * The by-name cascade is strictly scoped to the namespace of the agent: names are only unique within a namespace,
     * and another namespace may hold an agent of the same name. An agent without a namespace (platform-level) has no
     * by-name cascade. The scheduled prompts are not re-enabled when the agent is published again, and enabling one
     * of them while the agent is unpublished is refused by [io.whozoss.agentos.scheduledPrompt.ScheduledPromptService].
     *
     * Not covered: a scheduled prompt without flowConfiguration, which runs the default loop configuration of its
     * agent; agent renames and deletions (by-name targets are not followed).
     */
    @Transactional
    override fun disable(id: UUID): AgentConfig {
        val existing =
            agentConfigRepository.findById(id)
                ?: throw ResourceNotFoundException("AgentConfig not found: $id")
        val disabled = agentConfigRepository.save(existing.copy(enabled = false))
        val disabledCount = scheduledPromptRepository.disableByAgentConfigId(id)
        if (disabledCount > 0) {
            logger.info { "[AgentConfigService] Disabled $disabledCount scheduled prompt(s) for disabled agentConfigId=$id" }
        }
        val disabledByNameCount = disableScheduledPromptsTargeting(existing)
        if (disabledByNameCount > 0) {
            logger.info {
                "[AgentConfigService] Disabled $disabledByNameCount scheduled prompt(s) targeting disabled agent " +
                    "'${existing.name}' (agentConfigId=$id) by act.agentName"
            }
        }
        return disabled
    }

    /**
     * Disables the enabled scheduled prompts of the namespace of [agent] whose `act.agentName` is the name of [agent],
     * and returns how many. The filtering is done here, on the parsed payload: a namespace has few scheduled prompts
     * and this only runs when an agent is unpublished.
     */
    private fun disableScheduledPromptsTargeting(agent: AgentConfig): Int {
        val namespaceId = agent.namespaceId ?: return 0
        val targeting =
            scheduledPromptRepository
                .findEnabledByNamespaceId(namespaceId)
                .filter { it.actAgentName.equals(agent.name, ignoreCase = true) }
        targeting.forEach { scheduledPromptRepository.updateEnabled(it.id, enabled = false) }
        return targeting.size
    }

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

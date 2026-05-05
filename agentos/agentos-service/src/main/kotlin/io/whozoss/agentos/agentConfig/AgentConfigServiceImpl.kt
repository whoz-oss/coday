package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Delegates all persistence operations to [AgentConfigRepository].
 */
@Service
class AgentConfigServiceImpl(
    private val agentConfigRepository: AgentConfigRepository,
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

    override fun findDefault(namespaceId: UUID): AgentConfig =
        agentConfigRepository
            .findByParent(namespaceId)
            // TODO: add a dedicated findOldestByParent repository method to avoid full scan
            .minByOrNull { it.metadata.created }
            ?: DEFAULT_AGENT_CONFIG

    companion object {
        /**
         * Built-in fallback agent returned when a namespace has no persisted [AgentConfig].
         *
         * Uses a stable UUID derived from the name so the identity is consistent across
         * restarts. [modelName] is null: the namespace's default [AiModel] will be used.
         */
        val DEFAULT_AGENT_CONFIG =
            AgentConfig(
                metadata = EntityMetadata(id = UUID.nameUUIDFromBytes("default-agent".toByteArray())),
                namespaceId = UUID.fromString("00000000-0000-0000-0000-000000000000"),
                name = "Default Agent",
                description = "General-purpose agent. Delegates to specialised agents when appropriate.",
                instructions = null,
                modelName = null,
            )
    }
}

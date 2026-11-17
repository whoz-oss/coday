package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.exception.ConflictException
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

/**
 * Creates a built-in agent in a namespace when the namespace owns no agent of that name, and does nothing
 * otherwise. It is the shared mechanics of the seeders of the default agents (`Coday`, `Searcher`):
 *
 * - **existence, not content**: only the namespace's own agents are looked at (a disabled one counts, a
 *   platform agent of the same name does not), so an agent the namespace already owns, ours or one a user made,
 *   belongs to its administrators and is never rewritten;
 * - **create-only, never delete**;
 * - **concurrency**: within a process the caller serialises the calls for one namespace. Across processes the
 *   only guard is [AgentConfigService.create] refusing a duplicate name, a check followed by a write, so two
 *   instances could both create the agent in theory. When a creation is refused or conflicts and the namespace
 *   owns the agent by then, that is a lost race, logged at info level and never a failure.
 */
class NamespaceAgentCreator(
    private val agentConfigService: AgentConfigService,
) {
    /**
     * The agent named [agentName] that [namespaceId] owns, created from [build] when it owns none.
     *
     * @return the agent the namespace owns (the existing one, or the one just created), or null when a concurrent
     *   creation conflicted and the agent cannot be read back yet.
     */
    fun ensureIn(
        namespaceId: UUID,
        agentName: String,
        build: () -> AgentConfig,
    ): AgentConfig? {
        ownAgent(namespaceId, agentName)?.let {
            logger.debug { "[AgentSeeder] Namespace $namespaceId already owns agent '$agentName', leaving it untouched." }
            return it
        }
        return create(namespaceId, agentName, build)
    }

    /**
     * `findByName` is not used on purpose: it falls back to platform agents, which would wrongly report that the
     * namespace has one.
     */
    private fun ownAgent(
        namespaceId: UUID,
        agentName: String,
    ): AgentConfig? =
        agentConfigService
            .findByNamespace(namespaceId, withDisabled = true)
            .firstOrNull { it.name.equals(agentName, ignoreCase = true) }

    private fun create(
        namespaceId: UUID,
        agentName: String,
        build: () -> AgentConfig,
    ): AgentConfig? =
        try {
            agentConfigService.create(build()).also {
                logger.info { "[AgentSeeder] Created agent '$agentName' in namespace $namespaceId (model ${it.modelName})." }
            }
        } catch (e: ConflictException) {
            lostRace(namespaceId, agentName, e)
        } catch (e: DataIntegrityViolationException) {
            lostRace(namespaceId, agentName, e)
        } catch (e: IllegalArgumentException) {
            // AgentConfigService refuses a duplicate name this way. It is only the lost race when the namespace
            // owns the agent now; any other refusal is a real failure and must not be swallowed.
            val own = ownAgent(namespaceId, agentName) ?: throw e
            logConcurrentCreation(namespaceId, agentName, e)
            own
        }

    private fun lostRace(
        namespaceId: UUID,
        agentName: String,
        e: Exception,
    ): AgentConfig? {
        logConcurrentCreation(namespaceId, agentName, e)
        return ownAgent(namespaceId, agentName)
    }

    private fun logConcurrentCreation(
        namespaceId: UUID,
        agentName: String,
        e: Exception,
    ) {
        // Another instance created it first.
        logger.info(e) { "[AgentSeeder] Agent '$agentName' created concurrently in namespace $namespaceId, skipping." }
    }

    companion object : KLogging()
}

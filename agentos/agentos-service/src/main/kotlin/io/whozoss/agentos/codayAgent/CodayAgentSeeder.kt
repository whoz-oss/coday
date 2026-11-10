package io.whozoss.agentos.codayAgent

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.configAgent.ConfigAgentIntegration
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Owns what the default agent [AGENT_NAME] (`Coday`) is, the friendly default agent of an AgentOS instance,
 * and makes sure a given namespace has one: [ensureIn]. Declared by [CodayAgentProperties.enabled].
 *
 * The agent is **namespace-scoped**: each namespace gets its own `Coday`, created in it
 * (`AgentConfig.namespaceId` is the namespace id). It is deliberately not a platform agent: a platform-level
 * agent named `Coday` could clash with an agent of the same name that a namespace has, whereas an agent owned
 * by one namespace can never clash with the agents of another. Nothing is created at startup, because the
 * namespaces do not exist yet: [io.whozoss.agentos.user.PersonalNamespaceProvisioner] calls [ensureIn] for the
 * personal namespace of each user, then deploys the agent there.
 *
 * The agent runs on the model alias `BIG`, can read the configuration and propose changes through the
 * built-in `AGENTOS_CONFIG` integration (never applying them itself), and works on files through
 * `CASE_FILE_EXCHANGE` (the files of the conversation) and `NAMESPACE_FILE_EXCHANGE` (the shared folder
 * of the namespace, which for a personal namespace is the files folder the user picked in the first-run
 * wizard). Both are declared on the agent itself even though
 * `agentos.exchange.tools.*-enabled-by-default` are on by default: the declaration guarantees the
 * access when an operator turns those defaults off. The namespace exchange still requires the
 * invoking user to hold Namespace READ, and write follows Namespace WRITE. Its instructions
 * ship as the classpath resource [INSTRUCTIONS_RESOURCE].
 *
 * An existing `Coday` keeps the integrations it was created with (see "existence, not content"
 * below): it is never rewritten to add `NAMESPACE_FILE_EXCHANGE`. While the platform defaults are on, an
 * older `Coday` that does not mention the key still gets the namespace exchange through the default; if an
 * operator turns the default off, an administrator has to add the key to it through the UI or the API.
 *
 * Same semantics as [io.whozoss.agentos.queryUser.QueryUserConfigSeeder]:
 * - **existence, not content**: it only acts when the namespace owns no agent named [AGENT_NAME] (case
 *   insensitive, a disabled one counts). Whether that agent is ours or one the user made, it belongs to the
 *   namespace's administrators and is never rewritten. Only the namespace's own agents are looked at: a
 *   platform agent of the same name does not count, since it is not what the namespace owns;
 * - **create-only, never delete**: disabling the property later leaves existing agents in place;
 * - **concurrency**: within this process the calls for one namespace are serialised by the caller (the
 *   provisioner holds a per-user lock). Across processes there is no database uniqueness constraint on agent
 *   names, only the refusal of a duplicate name by [AgentConfigService.create], which is a check followed by
 *   a write. Two instances could therefore both create a `Coday` in a namespace in theory. The window is
 *   tiny: it requires two instances to provision the same brand-new namespace at the same moment. When a
 *   creation is refused or conflicts and the namespace owns the agent by then, that is a lost race, logged at
 *   info level and never a failure.
 */
@Service
class CodayAgentSeeder(
    private val properties: CodayAgentProperties,
    private val agentConfigService: AgentConfigService,
) {
    /**
     * The `Coday` agent that [namespaceId] owns, created when it owns none.
     *
     * @return the agent the namespace owns (the existing one, or the one just created), or null when
     *   `agentos.defaults.coday-agent.enabled` is false (nothing is created, nothing is looked up), or when a
     *   concurrent creation conflicted and the agent cannot be read back yet.
     */
    fun ensureIn(namespaceId: UUID): AgentConfig? {
        if (!properties.enabled) return null
        ownCoday(namespaceId)?.let {
            logger.debug { "[CodaySeeder] Namespace $namespaceId already owns agent '$AGENT_NAME', leaving it untouched." }
            return it
        }
        return create(namespaceId)
    }

    /**
     * The agent named [AGENT_NAME] that the namespace itself owns, disabled or not. `findByName` is not used on
     * purpose: it falls back to platform agents, which would wrongly report that the namespace has one.
     */
    private fun ownCoday(namespaceId: UUID): AgentConfig? =
        agentConfigService
            .findByNamespace(namespaceId, withDisabled = true)
            .firstOrNull { it.name.equals(AGENT_NAME, ignoreCase = true) }

    private fun create(namespaceId: UUID): AgentConfig? =
        try {
            agentConfigService.create(defaultAgent(namespaceId)).also {
                logger.info { "[CodaySeeder] Created agent '$AGENT_NAME' in namespace $namespaceId (model $MODEL_NAME)." }
            }
        } catch (e: ConflictException) {
            lostRace(namespaceId, e)
        } catch (e: DataIntegrityViolationException) {
            lostRace(namespaceId, e)
        } catch (e: IllegalArgumentException) {
            // AgentConfigService refuses a duplicate name this way. It is only the lost race when the namespace
            // owns the agent now; any other refusal is a real failure and must not be swallowed.
            val own = ownCoday(namespaceId) ?: throw e
            logConcurrentCreation(namespaceId, e)
            own
        }

    private fun lostRace(
        namespaceId: UUID,
        e: Exception,
    ): AgentConfig? {
        logConcurrentCreation(namespaceId, e)
        return ownCoday(namespaceId)
    }

    private fun logConcurrentCreation(
        namespaceId: UUID,
        e: Exception,
    ) {
        // Another instance created it first.
        logger.info(e) { "[CodaySeeder] Agent '$AGENT_NAME' created concurrently in namespace $namespaceId, skipping." }
    }

    private fun defaultAgent(namespaceId: UUID) =
        AgentConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            name = AGENT_NAME,
            description = DESCRIPTION,
            instructions = loadInstructions(),
            modelName = MODEL_NAME,
            enabled = true,
            // A null value enables the integration with all its tools.
            integrations =
                mapOf(
                    ConfigAgentIntegration.TYPE to null,
                    ExchangeIntegrationTypes.CASE to null,
                    ExchangeIntegrationTypes.NAMESPACE to null,
                ),
        )

    private fun loadInstructions(): String =
        ClassPathResource(INSTRUCTIONS_RESOURCE).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    companion object : KLogging() {
        /**
         * Name of the default agent, a **contract**: the personal namespace provisioner looks for it by this name
         * (case insensitive), names it as the default agent of the namespace, and the first-run wizard expects it.
         * Renaming it silently detaches every namespace that relies on it.
         */
        const val AGENT_NAME = "Coday"

        /** The model alias the agent runs on, resolved through the platform models like any alias. */
        const val MODEL_NAME = "BIG"

        const val INSTRUCTIONS_RESOURCE = "agents/coday-instructions.md"

        private const val DESCRIPTION = "The friendly default agent of this AgentOS: helps you get started and configure it."
    }
}

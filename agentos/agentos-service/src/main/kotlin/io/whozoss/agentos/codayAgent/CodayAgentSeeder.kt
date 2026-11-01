package io.whozoss.agentos.codayAgent

import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.configAgent.ConfigAgentIntegration
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.PersonalNamespaceProvisioner
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Reconciles, at startup, the existence of the platform agent `Coday`, the friendly default assistant
 * of an AgentOS instance, declared by [CodayAgentProperties.enabled].
 *
 * The agent runs on the model alias `BIG`, can read the configuration and propose changes through the
 * built-in `AGENTOS_CONFIG` integration (never applying them itself), and works on files through
 * `CASE_FILE_EXCHANGE` (the files of the conversation) and `NAMESPACE_FILE_EXCHANGE` (the shared folder
 * of the namespace, which for a personal namespace is the files folder the user picked in the first-run
 * wizard). The namespace exchange is granted on the agent itself, not through
 * `agentos.exchange.tools.namespace-enabled-by-default`, which would reach every agent. It still
 * requires the invoking user to hold Namespace READ, and write follows Namespace WRITE. Its instructions
 * ship as the classpath resource [INSTRUCTIONS_RESOURCE].
 *
 * An already-seeded `Coday` keeps the integrations it was created with (see "existence, not content"
 * below): an administrator has to add `NAMESPACE_FILE_EXCHANGE` to it through the UI or the API.
 *
 * Same semantics as [io.whozoss.agentos.queryUser.QueryUserConfigSeeder]:
 * - **existence, not content**: it only acts when no platform agent is named `Coday`; once the row
 *   exists it belongs to the administrator and startup never rewrites it;
 * - **create-only, never delete**: disabling the property later leaves an existing agent in place;
 * - **concurrent startups**: several instances may all see the agent missing; the loser's conflict is
 *   expected and logged at info level, never an aborted startup.
 *
 * ## Order against the bootstrap admin
 *
 * No order between this runner and [io.whozoss.agentos.bootstrap.BootstrapServiceImpl] is relied on.
 * When the user came first, the agent created here is not yet deployed on that user's personal
 * namespace, so [PersonalNamespaceProvisioner.ensureForAllUsers] is called after creating it; when the
 * agent came first, the provisioner finds it when the user's creation event arrives.
 */
@Service
@Order(110)
class CodayAgentSeeder(
    private val properties: CodayAgentProperties,
    private val agentConfigService: AgentConfigService,
    private val personalNamespaceProvisioner: PersonalNamespaceProvisioner,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = seed()

    internal fun seed() {
        val existing = agentConfigService.findByName(null, AGENT_NAME)
        when {
            existing != null && properties.enabled -> logger.info { "[CodaySeeder] Platform agent '$AGENT_NAME' already exists, leaving it untouched." }
            existing != null -> warnDisabledButPresent()
            properties.enabled -> createDefault()
            else -> Unit // disabled and nothing persisted: nothing to reconcile
        }
    }

    private fun createDefault() {
        try {
            agentConfigService.create(defaultAgent())
            logger.info { "[CodaySeeder] Created platform agent '$AGENT_NAME' (model $MODEL_NAME)." }
        } catch (e: ConflictException) {
            logConcurrentCreation(e)
        } catch (e: DataIntegrityViolationException) {
            logConcurrentCreation(e)
        } catch (e: IllegalArgumentException) {
            // AgentConfigService refuses a duplicate name this way. It is only the lost race when the agent
            // is there now; any other refusal is a real failure and must not be swallowed.
            if (agentConfigService.findByName(null, AGENT_NAME) == null) throw e
            logConcurrentCreation(e)
        }
        personalNamespaceProvisioner.ensureForAllUsers()
    }

    private fun logConcurrentCreation(e: Exception) {
        // Another instance won the startup race; the constraint did its job.
        logger.info(e) { "[CodaySeeder] Platform agent '$AGENT_NAME' created concurrently, skipping." }
    }

    private fun defaultAgent() =
        AgentConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = null,
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

    private fun warnDisabledButPresent() {
        logger.warn {
            "[CodaySeeder] agentos.defaults.coday-agent.enabled is false but the platform agent '$AGENT_NAME' still " +
                "exists. This seeder never deletes persisted data. To remove it, delete or disable that agent through the API."
        }
    }

    private fun loadInstructions(): String =
        ClassPathResource(INSTRUCTIONS_RESOURCE).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    companion object : KLogging() {
        /**
         * Name of the seeded platform agent, a **contract**: the personal namespace provisioner deploys it
         * by this name and names it as the default agent, and the first-run wizard expects it. Renaming it
         * silently detaches every namespace that relies on it.
         */
        const val AGENT_NAME = "Coday"

        /** The model alias the agent runs on, resolved through the platform models like any alias. */
        const val MODEL_NAME = "BIG"

        const val INSTRUCTIONS_RESOURCE = "agents/coday-instructions.md"

        private const val DESCRIPTION = "The friendly default assistant of this AgentOS: helps you get started and configure it."
    }
}

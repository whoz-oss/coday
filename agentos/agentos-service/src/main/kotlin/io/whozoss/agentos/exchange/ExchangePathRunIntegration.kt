package io.whozoss.agentos.exchange

import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.RunIntegrationCustomizer
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.util.substituteJsonTokens
import mu.KLogging
import org.springframework.stereotype.Component

/**
 * Resolves [ExchangeIntegrationTypes.CASE_PATH_TOKEN] and
 * [ExchangeIntegrationTypes.NAMESPACE_PATH_TOKEN] in the parameters of every integration of a run.
 *
 * ## What it is for
 *
 * An integration that runs commands or opens files names its directory as a fixed absolute path:
 * `workingDirectory` for BASH and TMUX, `cwd` for MCP_STDIO, `rootPath` for a FILE_ACCESS. None of
 * them has any notion of a case, so a command configured once runs in the same directory for every
 * case of the namespace — which is how an agent ends up editing the files of its own case and
 * building somewhere else entirely.
 *
 * These two tokens let an operator write *the case's directory* or *the namespace's shared
 * directory* instead of a path, in the field they already edit, and the service fills it in per
 * run.
 *
 * ## Why a token rather than rebinding known keys
 *
 * Rewriting `workingDirectory` for a list of integration types would mean service-side knowledge of
 * each plugin's parameter names, a new entry for every future plugin, and — worse — silently
 * relocating the commands of every existing BASH integration of every instance on upgrade. A token
 * carries none of that: it is opt-in, visible where the path is written, costs no per-type
 * knowledge, and works the same for a plugin nobody has written yet.
 *
 * ## No Git required
 *
 * The case token is resolved through [ExchangeRootResolver], the seam both the REST endpoints and
 * the agent file tools already use, so it answers with or without `agentos.git.workspaces.enabled`.
 * It resolves to the exchange root itself; in an equipped family the worktree is `repo/` underneath
 * and an integration that must run there writes `{{CASE_EXCHANGE}}/repo`.
 *
 * ## What it does not do
 *
 * It grants nothing. A BASH integration pointed at `{{NAMESPACE_EXCHANGE}}` runs as the service
 * user with no exchange permission check, unlike the agent's namespace file tools which require the
 * invoking user's Namespace READ (and WRITE to write). That is not a capability this adds — the
 * same integration could already be given the namespace path verbatim — but it is now easy enough
 * to write that it has to be said out loud. See the Safety section of `docs/file-exchange.md`.
 */
@Component
class ExchangePathRunIntegration(
    private val exchangeRootResolver: ExchangeRootResolver,
    private val exchangeStorageService: ExchangeStorageService,
) : RunIntegrationCustomizer {
    override fun customize(
        configs: List<IntegrationConfig>,
        context: AgentExecutionContext,
    ): List<IntegrationConfig> {
        // Serialized once and only to decide whether anything has to be done: resolving the case
        // token costs the resolver a read of the case and of its family binding, and a run whose
        // integrations carry no token must not pay for it.
        val serialized = configs.mapNotNull { it.parameters?.toString() }
        val tokens = resolveTokens(serialized, context)
        if (tokens.isEmpty()) return configs
        return configs.map { config ->
            when (val parameters = config.parameters) {
                null -> config
                else -> config.copy(parameters = substituteJsonTokens(parameters, tokens))
            }
        }
    }

    /**
     * The tokens actually present in [serialized] that could be resolved for this run.
     *
     * A token left out of the map stays in the parameters verbatim, and the plugin that reads it
     * rejects it: `BashConfigParser` requires an absolute path, so the integration contributes no
     * tool and logs why. That is the intended outcome — a run with no case has no case directory,
     * and quietly falling back to another one would put the work somewhere nobody looks.
     */
    private fun resolveTokens(
        serialized: List<String>,
        context: AgentExecutionContext,
    ): Map<String, String> {
        val tokens = mutableMapOf<String, String>()
        if (serialized.any { it.contains(ExchangeIntegrationTypes.CASE_PATH_TOKEN) }) {
            caseExchangePath(context)?.let { tokens[ExchangeIntegrationTypes.CASE_PATH_TOKEN] = it }
        }
        if (serialized.any { it.contains(ExchangeIntegrationTypes.NAMESPACE_PATH_TOKEN) }) {
            tokens[ExchangeIntegrationTypes.NAMESPACE_PATH_TOKEN] =
                exchangeStorageService
                    .namespaceRoot(context.namespaceId)
                    .toAbsolutePath()
                    .normalize()
                    .toString()
        }
        return tokens
    }

    /**
     * The case's exchange directory, or null when this resolution has no live case — a definition
     * preview, or any path that resolves an agent outside a run.
     *
     * `requireUsable` is deliberate and mirrors [io.whozoss.agentos.git.GitToolsRunIntegration]: a
     * family whose workspace is still being prepared or has failed must not have commands pointed
     * at it, and the conflict it raises says which of the two happened.
     */
    private fun caseExchangePath(context: AgentExecutionContext): String? {
        val caseId = context.caseId
        val caseCreatedAt = context.caseCreatedAt
        if (caseId == null || caseCreatedAt == null) {
            logger.warn {
                "${ExchangeIntegrationTypes.CASE_PATH_TOKEN} is used by an integration but this resolution " +
                    "has no live case; the token is left unresolved and its integration will provide no tool"
            }
            return null
        }
        return exchangeRootResolver
            .resolve(caseId, context.namespaceId, caseCreatedAt)
            .requireUsable()
            .toAbsolutePath()
            .normalize()
            .toString()
    }

    companion object : KLogging()
}

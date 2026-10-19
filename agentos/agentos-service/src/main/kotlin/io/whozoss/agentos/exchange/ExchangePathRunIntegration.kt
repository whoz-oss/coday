package io.whozoss.agentos.exchange

import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.RunIntegrationCustomizer
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.util.jsonContainsToken
import io.whozoss.agentos.util.substituteJsonTokens
import mu.KLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Resolves [ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN] and
 * [ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN] in the parameters of every integration of a
 * run.
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
 * ## What it grants, and what it does not
 *
 * The case token carries the same permission rule as the agent's own case file tools (see
 * [io.whozoss.agentos.agent.AgentServiceImpl.buildExchangeTools]): the run's own directory needs no
 * gate, since the invoking user already holds Case WRITE on that case to have reached this point,
 * but a directory owned by *another* case — a family's shared workspace — additionally requires the
 * user's permission on that owner. Delegation does not confer its rights, and a shell pointed at a
 * directory is a write surface, so the check asks for WRITE exactly as
 * [io.whozoss.agentos.git.GitToolsRunIntegration] does before handing over the same worktree.
 *
 * The namespace token grants nothing and checks nothing: a BASH integration pointed at
 * `{{NAMESPACE_EXCHANGE}}` runs as the service user, unlike the agent's namespace file tools which
 * require the invoking user's Namespace READ (and WRITE to write). That is not a capability this
 * adds — the namespace directory has one fixed path per namespace, so the same integration could
 * already be given it verbatim — but it is now easy enough to write that it has to be said out
 * loud. See the Safety section of `docs/file-exchange.md`.
 */
@Component
class ExchangePathRunIntegration(
    private val exchangeRootResolver: ExchangeRootResolver,
    private val exchangeStorageService: ExchangeStorageService,
    private val exchangeCapabilityService: ExchangeCapabilityService,
) : RunIntegrationCustomizer {
    override fun customize(
        configs: List<IntegrationConfig>,
        context: AgentExecutionContext,
    ): List<IntegrationConfig> {
        val tokens = resolveTokens(configs, context)
        return when {
            tokens.isEmpty() -> configs
            else ->
                configs.map { config ->
                    when (val parameters = config.parameters) {
                        null -> config
                        else -> config.copy(parameters = substituteJsonTokens(parameters, tokens))
                    }
                }
        }
    }

    /**
     * The tokens actually present in [configs] that could be resolved for this run.
     *
     * A token left out of the map stays in the parameters verbatim, and the plugin that reads it
     * rejects it: `BashConfigParser` requires an absolute path, so the integration contributes no
     * tool and logs why. That is the intended outcome — a run with no case, or one whose user may
     * not reach the directory, has no case directory, and quietly falling back to another one would
     * put the work somewhere nobody looks.
     */
    private fun resolveTokens(
        configs: List<IntegrationConfig>,
        context: AgentExecutionContext,
    ): Map<String, String> {
        val tokens = mutableMapOf<String, String>()
        if (configs.anyCarries(ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN)) {
            caseExchangePath(context)?.let { tokens[ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN] = it }
        }
        if (configs.anyCarries(ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN)) {
            tokens[ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN] =
                exchangeStorageService
                    .namespaceRoot(context.namespaceId)
                    .toAbsolutePath()
                    .normalize()
                    .toString()
        }
        return tokens
    }

    /**
     * Whether any of these configs carries [token] in its parameters.
     *
     * Asked before resolving what a token stands for: the case token costs the resolver a read of
     * the case and of its family binding, and a run whose integrations carry no token must not pay
     * for it.
     */
    private fun List<IntegrationConfig>.anyCarries(token: String): Boolean =
        any { jsonContainsToken(it.parameters, token) }

    /**
     * The case's exchange directory, or null when this resolution has no live case — a definition
     * preview, or any path that resolves an agent outside a run.
     */
    private fun caseExchangePath(context: AgentExecutionContext): String? {
        val caseId = context.caseId
        val caseCreatedAt = context.caseCreatedAt
        return when {
            caseId == null || caseCreatedAt == null -> {
                logger.warn {
                    "${ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN} is used by an integration but this " +
                        "resolution has no live case; the token is left unresolved and its integration will " +
                        "provide no tool"
                }
                null
            }

            else -> accessibleCasePath(caseId, caseCreatedAt, context)
        }
    }

    /**
     * The resolved directory once the invoking user's rights on it are established, or null when
     * they are not.
     *
     * `requireUsable` comes after the permission check and is deliberate, mirroring
     * [io.whozoss.agentos.git.GitToolsRunIntegration]: a family whose workspace is still being
     * prepared or has failed must not have commands pointed at it, and the conflict it raises says
     * which of the two happened — which is not something to tell a caller who may not reach the
     * directory in the first place.
     */
    private fun accessibleCasePath(
        caseId: UUID,
        caseCreatedAt: Instant,
        context: AgentExecutionContext,
    ): String? {
        val root = exchangeRootResolver.resolve(caseId, context.namespaceId, caseCreatedAt)
        return when {
            canAccess(root, caseId, context.userId) ->
                root
                    .requireUsable()
                    .toAbsolutePath()
                    .normalize()
                    .toString()

            else -> {
                logger.warn {
                    "${ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN} resolves to the directory of case " +
                        "${root.ownerCaseId}, which the user of case $caseId may not write; the token is left " +
                        "unresolved and its integration will provide no tool"
                }
                null
            }
        }
    }

    /**
     * A case's own directory is reachable by definition; a directory shared by a family requires
     * the invoking user's permission on the case that owns it.
     */
    private fun canAccess(
        root: ResolvedExchangeRoot,
        caseId: UUID,
        userId: UUID?,
    ): Boolean =
        root.ownerCaseId == caseId ||
            exchangeCapabilityService.canAccessCase(userId?.toString(), caseId, root, Action.WRITE)

    companion object : KLogging()
}

package io.whozoss.agentos.exchange

import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.RunIntegrationCustomizer
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.util.jsonTokensPresent
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
 * Each token carries the permission rule of the agent's own file tools for the same directory (see
 * [io.whozoss.agentos.agent.AgentServiceImpl.buildExchangeTools]), because a shell pointed at a
 * directory is a write surface:
 * - the case token: the run's own directory needs no gate, since the invoking user already holds
 *   Case WRITE on that case to have reached this point, but a directory owned by *another* case
 *   — a family's shared workspace — additionally requires that user's permission on the owner.
 *   Delegation does not confer its rights. This is the rule `buildExchangeTools` applies, not the
 *   stricter one of [io.whozoss.agentos.git.GitToolsRunIntegration], which asks for Case WRITE even
 *   on the case's own directory;
 * - the namespace token: Namespace READ, the floor every REST namespace-file endpoint enforces.
 *   The token still grants nothing beyond that — a shell runs as the service user, and the
 *   namespace directory has one fixed path per namespace, so the same integration could always have
 *   been handed it verbatim — but asking the same question as the namespace file tools costs one
 *   query and removes an asymmetry nobody auditing this boundary would expect. See the Safety
 *   section of `docs/file-exchange.md`.
 *
 * ## An unresolvable token removes its integration from the run
 *
 * A token this run cannot resolve — no live case, no permission, a workspace still being prepared
 * — drops the integration that carries it, with no tool and no exception. The alternative would be
 * to substitute nothing and let the plugin reading the parameter refuse it, which is what this class
 * did at first and what only `BashConfigParser` actually does: TMUX passes the literal to
 * `tmux new-session -c` and discards the failure, FILE_ACCESS turns it into a *relative* path and
 * roots its tools under the service's working directory, MCP_STDIO puts it in its connection pool
 * key. Each would run or write somewhere nobody looks, and quietly. Deciding here costs no
 * per-plugin knowledge, which is the whole reason this is a token and not a rebinding of each
 * plugin's own parameter names, and [io.whozoss.agentos.git.GitToolsRunIntegration] already drops
 * its own config the same way when it cannot build its parameters.
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
        // One walk per config, before anything is resolved: this runs on every agent resolution of
        // every namespace, over every integration config of that namespace, and almost none of them
        // carries a token. The per-config answer is kept so that a config found clean is handed back
        // untouched rather than rebuilt.
        val carried = configs.map { config -> config to jsonTokensPresent(config.parameters, ALL_TOKENS) }
        val used = carried.flatMapTo(mutableSetOf()) { it.second }
        return when {
            used.isEmpty() -> configs
            else -> substituted(carried, resolveTokens(used, context))
        }
    }

    private fun substituted(
        carried: List<Pair<IntegrationConfig, Set<String>>>,
        resolved: Map<String, String>,
    ): List<IntegrationConfig> =
        carried.mapNotNull { (config, tokens) ->
            val unresolved = tokens.filterNot { it in resolved }
            when {
                // Guarding on the tokens this config carries, not on the run's: a config that
                // mentions none is the common case and must not be rebuilt node by node, and one
                // whose token stayed unresolved must not be rebuilt either, since nothing changes.
                tokens.isEmpty() -> config
                unresolved.isEmpty() -> config.copy(parameters = substituteJsonTokens(config.parameters, resolved))
                else -> {
                    logger.debug {
                        "Integration '${config.name}' provides no tool on this run: " +
                            "${unresolved.joinToString()} could not be resolved (the reason is logged above)"
                    }
                    null
                }
            }
        }

    /**
     * What each of [used] stands for on this run, leaving out the ones that cannot be resolved.
     *
     * A token left out of the map stays in the parameters verbatim, and the plugin that reads it
     * rejects it: `BashConfigParser` requires an absolute path, so the integration contributes no
     * tool and logs why. That is the intended outcome — a run with no case, or one whose user may
     * not reach the directory, has no case directory, and quietly falling back to another one would
     * put the work somewhere nobody looks.
     *
     * Only the tokens actually written somewhere are resolved: the case one costs the resolver a
     * read of the case and of its family binding, and a run that never mentions it must not pay
     * for that.
     */
    private fun resolveTokens(
        used: Set<String>,
        context: AgentExecutionContext,
    ): Map<String, String> {
        val tokens = mutableMapOf<String, String>()
        if (ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN in used) {
            caseExchangePath(context)?.let { tokens[ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN] = it }
        }
        if (ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN in used) {
            namespaceExchangePath(context)?.let { tokens[ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN] = it }
        }
        return tokens
    }

    /**
     * The namespace's shared directory, or null when the invoking user does not hold Namespace
     * READ — the floor [io.whozoss.agentos.agent.AgentServiceImpl] applies before granting a single
     * namespace file tool, and that every REST namespace-file endpoint enforces through
     * `@PreAuthorize`. A run with no identified user is denied fail-closed, as everywhere else.
     */
    private fun namespaceExchangePath(context: AgentExecutionContext): String? {
        val userId = context.userId?.toString()
        val canRead =
            userId != null &&
                exchangeCapabilityService.canRead(
                    userId = userId,
                    entityType = EntityType.NAMESPACE,
                    entityId = context.namespaceId.toString(),
                )
        return when {
            !canRead -> {
                logger.warn {
                    "${ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN} is used by an integration but the " +
                        "user of this run does not hold Namespace READ; the integration provides no tool"
                }
                null
            }

            else ->
                exchangeStorageService
                    .namespaceRoot(context.namespaceId)
                    .toAbsolutePath()
                    .normalize()
                    .toString()
        }
    }

    /**
     * The case's exchange directory, or null when this resolution has no live case — a definition
     * preview, or any path that resolves an agent outside a run.
     */
    private fun caseExchangePath(context: AgentExecutionContext): String? {
        val caseId = context.caseId
        val caseCreatedAt = context.caseCreatedAt
        return when {
            caseId == null || caseCreatedAt == null -> {
                // Debug, not warn: a definition preview and an agent listing both resolve without a
                // case, so on an instance with one token-carrying integration this is a per-request
                // non-event. The two refusals below are the ones worth a warning.
                logger.debug {
                    "${ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN} is used by an integration but this " +
                        "resolution has no live case; the integration provides no tool"
                }
                null
            }

            else -> accessibleCasePath(caseId, caseCreatedAt, context)
        }
    }

    /**
     * The resolved directory once the invoking user's rights on it are established and the
     * directory is usable, or null when either fails.
     *
     * An unavailable workspace is read here rather than through `requireUsable`, deliberately: a
     * `ConflictException` thrown from a [RunIntegrationCustomizer] would abort the resolution of
     * *every* agent of the namespace, since this sees every effective integration config and not
     * the ones the agent declares — a chat agent that references no integration included. Dropping
     * the integration protects the half-prepared worktree just as well, and only from the agents
     * that actually point at it.
     */
    private fun accessibleCasePath(
        caseId: UUID,
        caseCreatedAt: Instant,
        context: AgentExecutionContext,
    ): String? {
        val root = exchangeRootResolver.resolve(caseId, context.namespaceId, caseCreatedAt)
        return when {
            !canAccess(root, caseId, context.userId) -> {
                logger.warn {
                    "${ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN} resolves to the directory of case " +
                        "${root.ownerCaseId}, which the user of case $caseId may not write; the integration " +
                        "provides no tool"
                }
                null
            }

            root.unavailableReason != null -> {
                logger.warn {
                    "${ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN} cannot be resolved for case $caseId: " +
                        "${root.unavailableReason}; the integration provides no tool rather than pointing " +
                        "commands at a directory that is not ready"
                }
                null
            }

            else ->
                root.path
                    .toAbsolutePath()
                    .normalize()
                    .toString()
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

    companion object : KLogging() {
        private val ALL_TOKENS =
            listOf(
                ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN,
                ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN,
            )
    }
}

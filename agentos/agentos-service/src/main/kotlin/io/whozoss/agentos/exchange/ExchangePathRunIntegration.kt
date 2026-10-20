package io.whozoss.agentos.exchange

import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.RunIntegrationCustomizer
import io.whozoss.agentos.agent.RunIntegrationCustomizerOrder
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.util.jsonTokensPresent
import io.whozoss.agentos.util.substituteJsonTokens
import mu.KLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
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
 * Each token asks for a permission at least as strict as the agent's own file tools on the same
 * directory, because a directory handed to a shell is a write surface:
 * - the case token: the run's own directory needs no gate, since the invoking user already holds
 *   Case WRITE on that case to have reached this point, but a directory owned by *another* case
 *   — a family's shared workspace — additionally requires that user's permission on the owner.
 *   Delegation does not confer its rights. This is the rule
 *   [io.whozoss.agentos.agent.AgentServiceImpl.resolveCaseScope] applies, not the stricter one of
 *   [io.whozoss.agentos.git.GitToolsRunIntegration], which asks for Case WRITE even
 *   on the case's own directory. A run always has that identity, whatever started it:
 *   [io.whozoss.agentos.caseFlow.CaseServiceImpl] refuses to build a context without a user found
 *   in the case's event history, and an existing one, so a webhook or a scheduled prompt carries
 *   the identity its triggering event recorded. The only context with no user resolves no case
 *   either, being a definition preview;
 * - the namespace token: Namespace WRITE, which is stricter than the Namespace READ that gets the
 *   namespace file tools granted. Those tools are read-only without WRITE, and a directory handed
 *   to a shell has no read-only mode, so the equivalent of a read-only grant is no integration at
 *   all. Resolving on READ would hand a plain member, through one integration, the write surface
 *   that both the file tools and the REST namespace-file endpoints refuse them. That member still
 *   reads the whole namespace through their own read-only file tools. See the Safety section of
 *   `docs/file-exchange.md`.
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
 *
 * ## Order against the other customizer
 *
 * This one runs **after** [io.whozoss.agentos.git.GitToolsRunIntegration], pinned by
 * [RunIntegrationCustomizerOrder]. For almost everything the order is indifferent: that one only
 * touches `GIT` configs, and on those it writes its five keys with `put` on a deep copy,
 * unconditionally, so it wins on them from either side, their permission gates cannot disagree
 * (its own is the stricter, Case `WRITE` on the owning case even where this one short-circuits),
 * and neither writes a `{{TOKEN}}` spelling the other would then expand.
 *
 * One combination does depend on it. A `GIT` config whose parameters carry
 * [ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN], on a family whose workspace is not usable yet:
 * running first, that customizer reaches `requireUsable()` and raises a `ConflictException`, the
 * answer it gave before these tokens existed; running second, it would never see the config,
 * because this one drops it as unresolvable and the resolution succeeds without it. Git first keeps
 * the louder, older answer.
 */
@Component
@Order(RunIntegrationCustomizerOrder.EXCHANGE_PATHS)
class ExchangePathRunIntegration(
    private val exchangeRootResolver: ExchangeRootResolver,
    private val exchangeStorageService: ExchangeStorageService,
    private val exchangeCapabilityService: ExchangeCapabilityService,
) : RunIntegrationCustomizer {
    override fun customize(
        configs: List<IntegrationConfig>,
        context: AgentExecutionContext,
    ): List<IntegrationConfig> {
        // One walk per config, before anything is resolved. What this buys is bounded, and worth
        // stating plainly: the walk runs on every agent resolution, over every integration config
        // of the namespace, because that is what a RunIntegrationCustomizer is handed, before any
        // filtering by what the agent declares. So a single token-carrying config in a namespace
        // makes every resolution there pay the walk and one resolution of that token, a chat agent
        // referencing no integration included. The walk itself is in-memory over trees the caller
        // has just deserialized anyway; what it avoids is the database read behind a token nobody
        // wrote. The per-config answer is kept so that a config found clean is handed back
        // untouched rather than rebuilt.
        val carried = configs.map { config -> config to jsonTokensPresent(config.parameters, ALL_TOKENS) }
        val used = carried.flatMapTo(mutableSetOf()) { it.second }
        return when {
            used.isEmpty() -> configs
            else -> substituted(carried, resolveTokens(used, context), context.caseId != null)
        }
    }

    private fun substituted(
        carried: List<Pair<IntegrationConfig, Set<String>>>,
        resolved: Map<String, String>,
        isRun: Boolean,
    ): List<IntegrationConfig> =
        carried.mapNotNull { (config, tokens) ->
            when {
                // Guarding on the tokens this config carries, not on the run's: a config that
                // mentions none is the common case and must not be rebuilt node by node, and one
                // whose token stayed unresolved must not be rebuilt either, since nothing changes.
                tokens.isEmpty() -> config
                else -> {
                    val unresolved = tokens.filterNot { it in resolved }
                    when {
                        unresolved.isEmpty() ->
                            config.copy(
                                parameters = substituteJsonTokens(config.parameters, resolved.filterKeys { it in tokens }),
                            )

                        else -> {
                            logDrop(config, unresolved, isRun)
                            null
                        }
                    }
                }
            }
        }

    /**
     * An integration an operator configured is not there any more, which is the event worth a
     * warning on a real run, and the reasons behind it are logged at debug.
     *
     * Only on a real run. This customizer is handed every integration config of the namespace,
     * before any filtering by what the agent declares, and it also runs where there is no run at
     * all: a definition preview, an agent listing. One namespace holding one token-carrying config
     * would otherwise warn on every one of those requests, about an integration nobody asked for,
     * which is the same noise the three reasons were demoted to debug to avoid.
     */
    private fun logDrop(
        config: IntegrationConfig,
        unresolved: List<String>,
        isRun: Boolean,
    ) {
        val message = {
            "Integration '${config.name}' provides no tool: ${unresolved.joinToString()} could not be " +
                "resolved (enable debug on this class for the reason)"
        }
        when {
            isRun -> logger.warn(message)
            else -> logger.debug(message)
        }
    }

    /**
     * What each of [used] stands for on this run, leaving out the ones that cannot be resolved.
     *
     * A token left out of the map removes every integration that carries it, in [substituted].
     * That is the intended outcome — a run with no case, or one whose user may not reach the
     * directory, has no case directory, and quietly falling back to another one would put the work
     * somewhere nobody looks.
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
     * WRITE. A shell or an MCP server rooted there writes, and nothing can make it read-only, so
     * the question asked is the one [io.whozoss.agentos.agent.AgentServiceImpl] asks before handing
     * the namespace file tools their write tools, not the weaker Namespace READ that gets those
     * tools granted read-only. A run with no identified user is denied fail-closed, as everywhere
     * else.
     */
    private fun namespaceExchangePath(context: AgentExecutionContext): String? {
        val userId = context.userId?.toString()
        val canWrite =
            userId != null &&
                exchangeCapabilityService.canWrite(
                    userId = userId,
                    entityType = EntityType.NAMESPACE,
                    entityId = context.namespaceId.toString(),
                )
        return when {
            !canWrite -> {
                logger.debug {
                    "${ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN} is used by an integration but the " +
                        "user of this run does not hold Namespace WRITE; the integration provides no tool"
                }
                null
            }

            else ->
                materialised(
                    exchangeStorageService.namespaceRoot(context.namespaceId),
                    ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN,
                )
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
                // A definition preview and an agent listing both resolve without a case, so on an
                // instance with one token-carrying integration this is a per-request non-event.
                // Like the other two reasons, it is logged at debug and the drop itself warns.
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
                logger.debug {
                    "${ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN} resolves to the directory of case " +
                        "${root.ownerCaseId}, which the user of case $caseId may not write; the integration " +
                        "provides no tool"
                }
                null
            }

            root.unavailableReason != null -> {
                logger.debug {
                    "${ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN} cannot be resolved for case $caseId: " +
                        "${root.unavailableReason}; the integration provides no tool rather than pointing " +
                        "commands at a directory that is not ready"
                }
                null
            }

            else -> materialised(root.path, ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN)
        }
    }

    /**
     * The absolute path of [root], created if it does not exist yet, or null when it cannot be.
     *
     * A resolved token has to name a directory that exists: `ProcessBuilder.directory` fails on a
     * missing one, so a BASH integration would answer an `IOException` on every command. Nothing
     * else on this path creates it. An exchange root is materialised by
     * [ExchangeToolGrantService.grantTools] when the agent is granted the matching file scope, and
     * an agent can point an integration at a directory without holding that scope: the platform
     * default is off, and an agent can opt out with an empty list. The REST side deliberately
     * creates nothing, which is what keeps a never-written scope from leaving empty shard
     * directories behind.
     *
     * A failure is logged and drops the integration, like any other unresolvable token, rather than
     * aborting the resolution of every agent of the namespace.
     */
    private fun materialised(
        root: Path,
        token: String,
    ): String? {
        val path = root.toAbsolutePath().normalize()
        return runCatching {
            Files.createDirectories(path)
            path.toString()
        }.getOrElse { failure ->
            logger.warn(failure) {
                "$token resolves to $path, which could not be created; the integration provides no tool"
            }
            null
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
            setOf(
                ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN,
                ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN,
            )
    }
}

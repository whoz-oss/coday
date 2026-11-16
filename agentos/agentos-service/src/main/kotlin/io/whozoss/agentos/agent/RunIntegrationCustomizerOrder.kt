package io.whozoss.agentos.agent

/**
 * The order the [RunIntegrationCustomizer] beans are folded in, pinned rather than left to the bean
 * scan because one combination makes it observable.
 *
 * A `GIT` config whose parameters carry an exchange token, on a family whose workspace is not
 * usable yet: [io.whozoss.agentos.git.GitToolsRunIntegration] raises a `ConflictException` from
 * `requireUsable()`, while [io.whozoss.agentos.exchange.ExchangePathRunIntegration] drops the
 * config and lets the resolution succeed. Git runs first, so that case keeps answering the 409 it
 * answered before the tokens existed.
 */
object RunIntegrationCustomizerOrder {
    /** Binds the `GIT` integration to the family's worktree. */
    const val GIT_TOOLS: Int = 100

    /** Resolves the exchange path tokens in every integration's parameters. */
    const val EXCHANGE_PATHS: Int = 200
}

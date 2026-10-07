package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.session.*` configuration tree.
 *
 * [defaultRepoRoot] is the destination repository root the DAG sequencer runs
 * `code` verifications against and hands to the agent-turn capability when a
 * run request does not supply an explicit `repoRoot`. It is null by default:
 * requiring an explicit root keeps the boundary fail-closed instead of silently
 * guessing a filesystem location.
 */
@ConfigurationProperties(prefix = "factory.session")
data class SessionProperties(
    val defaultRepoRoot: String? = null,
)

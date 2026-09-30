package io.whozoss.agentos.git.core

/**
 * Reference namespaces used by managed Git operations.
 *
 * AgentOS keeps its own references under `refs/agentos/` so that nothing it needs internally can be
 * moved or deleted by an agent working on branches, and so that `refs/remotes/origin/` keeps the
 * meaning `push --force-with-lease` relies on.
 */
object GitRefs {
    const val HEADS: String = "refs/heads/"

    const val REMOTE_ORIGIN: String = "refs/remotes/origin/"

    /** Main-branch commit a case family was created from, frozen per root case. */
    const val AGENTOS_BASE: String = "refs/agentos/base/"

    /** Last observed remote state of a family's branch, fetched without moving `refs/remotes/origin/`. */
    const val AGENTOS_OBSERVED: String = "refs/agentos/observed/"

    /** Scratch references advertised during a managed fetch negotiation. */
    const val AGENTOS_NEGOTIATION: String = "refs/agentos/negotiation"

    fun head(branch: String): String = HEADS + branch

    fun remoteTracking(branch: String): String = REMOTE_ORIGIN + branch
}

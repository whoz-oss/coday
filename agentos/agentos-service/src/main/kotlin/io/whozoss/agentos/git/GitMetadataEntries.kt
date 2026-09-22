package io.whozoss.agentos.git

import io.whozoss.agentos.exchange.ExchangeReservedEntries
import org.springframework.stereotype.Component

/**
 * Keeps Git metadata out of the Exchange file API and the file tools.
 *
 * An equipped case has its worktree in `repo/`, and an agent can create a repository from its
 * shell, so a `.git` can sit in any scope. Deleting it detaches the worktree, rewriting a linked
 * worktree's `.git` pointer file redirects later server-side commands at another worktree, and
 * reading `config` discloses the remote URL and absolute server paths. Git itself keeps working:
 * this guards file access, not the commands the service runs.
 *
 * Always registered, whether a namespace is associated or the `GIT` plugin is loaded.
 */
@Component
class GitMetadataEntries : ExchangeReservedEntries {
    override fun names(): Set<String> = setOf(GIT_METADATA)

    private companion object {
        /** A directory in a clone, a pointer file in a linked worktree. */
        const val GIT_METADATA = ".git"
    }
}

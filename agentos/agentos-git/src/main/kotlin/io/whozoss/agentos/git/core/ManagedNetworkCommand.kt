package io.whozoss.agentos.git.core

/**
 * Git commands that reach a remote. The runner sends them to [GitNetworkCommands], which runs them
 * in a private context: they are the only commands allowed to carry credentials.
 */
internal enum class ManagedNetworkCommand(
    val gitName: String,
) {
    CLONE("clone"),
    LS_REMOTE("ls-remote"),
    FETCH("fetch"),
    ;

    companion object {
        /** The managed command an invocation starts with, or null for a local command. */
        fun of(invocation: GitInvocation): ManagedNetworkCommand? =
            entries.firstOrNull { it.gitName == invocation.args.firstOrNull() }
    }
}

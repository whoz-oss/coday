package io.whozoss.agentos.plugins.git

/** Where the worktree stands: branch, commit, remote comparison and changed files. */
internal class GitStatusTool(
    prefix: String,
    private val workspace: GitWorkspace,
) : GitTool<Unit>(prefix, "git_status") {
    override val description: String =
        """
        Show the Git state of this case's workspace: current branch (or detached HEAD), current commit,
        whether the branch was pushed, and the changed files in `git status --porcelain` format.
        The workspace is created and removed by AgentOS; you work in it with these Git tools.
        """.trimIndent()

    override val paramType: Class<Unit>? = null

    override val inputSchema: String = """{"type":"object","properties":{},"additionalProperties":false}"""

    override fun run(input: Unit?): String {
        val branch = workspace.branch()
        val head = workspace.head()
        val changes = workspace.status()
        return buildString {
            appendLine(if (branch == null) "HEAD is detached at ${head ?: "no commit"}" else "On branch $branch at $head")
            if (branch != null) {
                val tracked = workspace.trackedCommit(branch)
                appendLine(
                    when (tracked) {
                        null -> "The branch has not been pushed"
                        head -> "The branch matches the last pushed or fetched commit"
                        else -> "The branch differs from the last pushed or fetched commit $tracked"
                    },
                )
            }
            if (changes.isEmpty()) {
                append("No local changes")
            } else {
                appendLine("Changes (${changes.size}):")
                changes.take(MAX_ENTRIES).forEach { appendLine(it) }
                if (changes.size > MAX_ENTRIES) append("... ${changes.size - MAX_ENTRIES} more")
            }
        }.trim()
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}

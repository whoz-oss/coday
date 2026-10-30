package io.whozoss.agentos.plugins.git

/** Update the remote-tracking ref of one branch with the user's credentials. */
internal class GitFetchTool(
    prefix: String,
    private val workspace: GitWorkspace,
    private val access: GitForgeAccess,
) : GitTool<GitFetchTool.Input>(prefix, "git_fetch") {
    data class Input(val branch: String? = null)

    override val description: String =
        """
        Fetch one branch of the repository into origin/<branch>, for example before a rebase or merge.
        Defaults to the main branch. Local branches and files are not changed.
        """.trimIndent()

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        """
        {
            "type": "object",
            "properties": {
                "branch": {"type": "string", "description": "Remote branch to fetch; defaults to the main branch"}
            },
            "additionalProperties": false
        }
        """.trimIndent()

    override fun run(input: Input?): String {
        val branch = input?.branch?.trim()?.takeIf { it.isNotEmpty() } ?: workspace.mainBranch
        val commit = workspace.fetch(branch, access.token())
        return "origin/$branch is at $commit"
    }
}

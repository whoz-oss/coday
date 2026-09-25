package io.whozoss.agentos.plugins.git

/** Push the current branch with the user's credentials. Never the main branch. */
internal class GitPushTool(
    prefix: String,
    private val workspace: GitWorkspace,
    private val access: GitForgeAccess,
) : GitTool<GitPushTool.Input>(prefix, "git_push") {
    data class Input(val forceWithLease: Boolean? = null)

    override val description: String =
        """
        Push the current branch of this case's workspace to the branch of the same name, with your own credentials.
        The main branch is never pushed: open a pull request instead. After a rebase, set forceWithLease: the
        remote branch is then replaced only if it has not changed since your last push or fetch.
        """.trimIndent()

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        """
        {
            "type": "object",
            "properties": {
                "forceWithLease": {"type": "boolean", "description": "Replace the remote branch after a rebase, if nobody else changed it", "default": false}
            },
            "additionalProperties": false
        }
        """.trimIndent()

    override fun run(input: Input?): String {
        val branch = workspace.requireBranch("push")
        if (branch == workspace.mainBranch) {
            throw GitToolException("The main branch '$branch' is never pushed from a case: create a branch and open a pull request")
        }
        workspace.push(branch, access.token(), lease = input?.forceWithLease == true)
        return "Pushed $branch at ${workspace.head()}"
    }
}

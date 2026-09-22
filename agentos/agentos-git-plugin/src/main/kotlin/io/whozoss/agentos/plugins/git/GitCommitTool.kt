package io.whozoss.agentos.plugins.git

/** Stage and commit changes as the user running the case. */
internal class GitCommitTool(
    prefix: String,
    private val workspace: GitWorkspace,
    private val access: GitForgeAccess,
) : GitTool<GitCommitTool.Input>(prefix, "git_commit") {
    data class Input(val message: String? = null, val paths: List<String>? = null)

    override val description: String =
        """
        Commit changes of this case's workspace on the current branch, authored as the user running the case.
        Stages the given paths, or every change when none is given. Refused on a detached HEAD.
        """.trimIndent()

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        """
        {
            "type": "object",
            "properties": {
                "message": {"type": "string", "description": "Commit message"},
                "paths": {"type": "array", "items": {"type": "string"}, "description": "Paths relative to the repository root; omit to commit every change"}
            },
            "required": ["message"],
            "additionalProperties": false
        }
        """.trimIndent()

    override fun run(input: Input?): String {
        val message = input?.message?.takeIf { it.isNotBlank() } ?: throw GitToolException("A commit message is required")
        val branch = workspace.requireBranch("commit")
        val paths = input.paths.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val commit = workspace.commit(message, paths, access.identity(workspace.repositoryUrl))
        return "Committed $commit on $branch"
    }
}

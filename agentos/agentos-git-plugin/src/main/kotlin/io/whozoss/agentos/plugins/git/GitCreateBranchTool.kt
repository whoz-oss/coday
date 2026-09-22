package io.whozoss.agentos.plugins.git

/** Create a branch at the current commit and check it out. */
internal class GitCreateBranchTool(
    prefix: String,
    private val workspace: GitWorkspace,
) : GitTool<GitCreateBranchTool.Input>(prefix, "git_create_branch") {
    data class Input(val name: String? = null)

    override val description: String =
        "Create a Git branch at the current commit of this case's workspace and switch to it. Do this before committing."

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        """
        {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Branch name, for example feature/fix-exports"}
            },
            "required": ["name"],
            "additionalProperties": false
        }
        """.trimIndent()

    override fun run(input: Input?): String {
        val name = input?.name?.trim().orEmpty()
        if (name.isEmpty()) throw GitToolException("A branch name is required")
        workspace.createBranch(name)
        return "Switched to new branch $name at ${workspace.head()}"
    }
}

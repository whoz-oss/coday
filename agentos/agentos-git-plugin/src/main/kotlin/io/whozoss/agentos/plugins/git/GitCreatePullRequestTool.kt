package io.whozoss.agentos.plugins.git

import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.git.core.GitHubRepository
import io.whozoss.agentos.git.core.GitRefNames

/** Open a GitHub pull request for the pushed current branch, as the user running the case. */
internal class GitCreatePullRequestTool(
    prefix: String,
    private val workspace: GitWorkspace,
    private val access: GitForgeAccess,
    private val gitHub: GitHubApi,
) : GitTool<GitCreatePullRequestTool.Input>(prefix, "git_create_pull_request") {
    data class Input(
        val title: String? = null,
        val body: String? = null,
        val base: String? = null,
        val draft: Boolean? = null,
    )

    override val description: String =
        """
        Open a GitHub pull request from the current branch, which must be pushed first. It targets the main
        branch unless another base is given, and is opened with your own GitHub account.
        """.trimIndent()

    override val paramType: Class<Input> = Input::class.java

    override val inputSchema: String =
        """
        {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Pull request title"},
                "body": {"type": "string", "description": "Pull request description (Markdown)"},
                "base": {"type": "string", "description": "Target branch; defaults to the main branch"},
                "draft": {"type": "boolean", "description": "Open as a draft", "default": false}
            },
            "required": ["title"],
            "additionalProperties": false
        }
        """.trimIndent()

    override fun run(input: Input?): String {
        val title = input?.title?.trim()?.takeIf { it.isNotEmpty() } ?: throw GitToolException("A pull request title is required")
        val repository = GitHubRepository.fromRemoteUrl(workspace.repositoryUrl)
            ?: throw GitToolException("Pull requests can only be opened for github.com repositories")
        val branch = workspace.requireBranch("open a pull request")
        val base = input.base?.trim()?.takeIf { it.isNotEmpty() } ?: workspace.mainBranch
        if (!GitRefNames.isValidBranchName(base)) throw GitToolException("'$base' is not a valid branch name")
        if (branch == base) throw GitToolException("The pull request needs a branch other than its base '$base'")
        val head = workspace.head()
        if (workspace.trackedCommit(branch) != head) {
            throw GitToolException("Push '$branch' before opening its pull request: the remote branch is not at $head")
        }
        val response = gitHub.post(
            "repos/${repository.fullName}/pulls",
            access.token().api,
            mapOf("title" to title, "head" to branch, "base" to base, "body" to input.body, "draft" to (input.draft == true)),
        )
        val pull = response.body
        if (response.status != 201 || pull == null) {
            val reason = pull?.path("errors")?.firstOrNull()?.path("message")?.asText()?.takeIf { it.isNotBlank() }
                ?: pull?.path("message")?.asText()?.takeIf { it.isNotBlank() }
                ?: "no details"
            throw GitToolException("GitHub refused the pull request (HTTP ${response.status}): ${reason.take(500)}")
        }
        return "Opened pull request #${pull.path("number").asInt()}: ${pull.path("html_url").asText()}"
    }
}

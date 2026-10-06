package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.git.core.GitHubRepository
import io.whozoss.agentos.git.core.GitObjectIds
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.http.HttpClient

/** GitHub.com adapter. Other Git hosts remain usable without a PR status projection. */
@Component
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitHubPullRequests internal constructor(
    private val accounts: GitServiceAccountResolver,
    mapper: ObjectMapper,
    client: HttpClient,
) : GitHostingProvider {
    @Autowired
    constructor(accounts: GitServiceAccountResolver, mapper: ObjectMapper) : this(
        accounts, mapper, GitHubApi.newClient(),
    )

    private val api = GitHubApi(client, mapper)

    /** Another host is no failure: its pull request state is unknown, and nothing is sent to GitHub. */
    override fun inspect(settings: GitRepositorySettings, branch: String, headSha: String?): GitWorkspaceSummary =
        GitHubRepository.fromRemoteUrl(settings.repositoryUrl)
            ?.let { inspectOn(it, settings, branch, headSha) }
            ?: GitWorkspaceSummary(prState = PrState.UNKNOWN)

    private fun inspectOn(
        repository: GitHubRepository,
        settings: GitRepositorySettings,
        branch: String,
        headSha: String?,
    ): GitWorkspaceSummary {
        val fullName = repository.fullName
        val head = URLEncoder.encode("${repository.owner}:$branch", Charsets.UTF_8)
        val array = request(
            settings,
            "$fullName/pulls?state=all&head=$head&sort=updated&direction=desc&per_page=$PAGE_SIZE",
        )
        val matching = array.filter {
            it.path("head").path("ref").asText() == branch &&
                it.path("head").path("repo").path("full_name").asText().equals(fullName, true) &&
                it.path("base").path("repo").path("full_name").asText().equals(fullName, true)
        }
        val open = matching.filter { it.path("state").asText() == STATE_OPEN }
        val pr = openPullRequest(open, settings.mainBranch) ?: matching.firstOrNull()
            ?: findByHead(settings, fullName, branch, headSha)
            ?: return GitWorkspaceSummary(prState = PrState.NONE)
        return summary(pr)
    }

    /**
     * The open pull request of a branch. GitHub allows one per base branch: the one targeting the
     * main branch wins, and several left after that are ambiguous.
     */
    private fun openPullRequest(open: List<JsonNode>, mainBranch: String): JsonNode? {
        val towardsMain = open.filter { it.path("base").path("ref").asText() == mainBranch }
        return when {
            open.size <= 1 -> open.singleOrNull()
            towardsMain.size == 1 -> towardsMain.single()
            else -> error("Several open PRs reference this branch")
        }
    }

    private fun findByHead(settings: GitRepositorySettings, fullName: String, branch: String, headSha: String?): JsonNode? {
        // An agent may check out an existing PR under a local alias. GitHub's commit endpoint
        // also returns PRs that merely contain this commit: only an exact, unique PR head is
        // evidence of the association. Never attribute the latest merged PR to the main branch.
        if (headSha == null || branch == settings.mainBranch) return null
        require(headSha.lowercase().matches(GitObjectIds.FULL_ID)) { "Invalid Git commit SHA" }
        val candidates = request(settings, "$fullName/commits/${headSha.lowercase()}/pulls?per_page=$PAGE_SIZE").filter {
            it.path("base").path("repo").path("full_name").asText().equals(fullName, true) &&
                it.path("head").path("sha").asText().equals(headSha, true)
        }
        check(candidates.size <= 1) { "Several PRs have the current commit as their head; cannot determine its association" }
        return candidates.singleOrNull()
    }

    private fun request(settings: GitRepositorySettings, repositoryPath: String): JsonNode {
        // The API host is fixed; repository configuration cannot redirect service credentials.
        val response = api.get("repos/$repositoryPath", accounts.resolve(settings).secret)
        check(response.status == HttpURLConnection.HTTP_OK) { "PR status unavailable (HTTP ${response.status})" }
        val array = response.body
        // The response headers are not read, so a full page cannot be told apart from a truncated
        // one: refuse rather than guess, since a wrong answer here would be projected as a PR state.
        check(array != null && array.isArray && array.size() < PAGE_SIZE) {
            "PR result is incomplete; cannot determine its status"
        }
        return array
    }

    private fun summary(pr: JsonNode): GitWorkspaceSummary {
        val state = pr.path("state").asText()
        val merged = !pr.path("merged_at").isNull && !pr.path("merged_at").isMissingNode
        val projected = when {
            state == STATE_OPEN && pr.path("draft").asBoolean() -> PrState.DRAFT
            state == STATE_OPEN -> PrState.OPEN
            merged -> PrState.MERGED
            state == STATE_CLOSED -> PrState.CLOSED_UNMERGED
            else -> PrState.UNKNOWN
        }
        return GitWorkspaceSummary(
            prState = projected,
            prNumber = pr.path("number").asInt(),
            prUrl = pr.path("html_url").asText(),
            prHeadSha = pr.path("head").path("sha").asText(),
        )
    }

    private companion object {
        /** `per_page` of every call: a full page cannot be told apart from a truncated one. */
        const val PAGE_SIZE = 100

        // GitHub's own vocabulary, not ours: these are the values the API returns.
        const val STATE_OPEN = "open"
        const val STATE_CLOSED = "closed"
    }
}

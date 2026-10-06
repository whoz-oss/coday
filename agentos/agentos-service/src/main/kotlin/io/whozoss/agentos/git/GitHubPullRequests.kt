package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.git.GitWorkspaceStates.PR_CLOSED_UNMERGED
import io.whozoss.agentos.git.GitWorkspaceStates.PR_DRAFT
import io.whozoss.agentos.git.GitWorkspaceStates.PR_MERGED
import io.whozoss.agentos.git.GitWorkspaceStates.PR_NONE
import io.whozoss.agentos.git.GitWorkspaceStates.PR_OPEN
import io.whozoss.agentos.git.GitWorkspaceStates.UNKNOWN
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

    override fun inspect(settings: GitRepositorySettings, branch: String, headSha: String?): GitWorkspaceSummary {
        val repository = requireNotNull(GitHubRepository.fromRemoteUrl(settings.repositoryUrl)) {
            "Automatic PR status is currently supported for github.com repositories"
        }
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
        check(open.size <= 1) { "Several open PRs reference this branch" }
        val pr = open.firstOrNull() ?: matching.firstOrNull()
            ?: findByHead(settings, fullName, branch, headSha)
            ?: return GitWorkspaceSummary(prState = PR_NONE)
        return summary(pr)
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
        // A full page may be truncated: GitHub never says so, and a wrong answer here would be
        // projected as a PR state. Refuse rather than guess.
        check(array != null && array.isArray && array.size() < PAGE_SIZE) {
            "PR result is incomplete; cannot determine its status"
        }
        return array
    }

    private fun summary(pr: JsonNode): GitWorkspaceSummary {
        val state = pr.path("state").asText()
        val merged = !pr.path("merged_at").isNull && !pr.path("merged_at").isMissingNode
        val projected = when {
            state == STATE_OPEN && pr.path("draft").asBoolean() -> PR_DRAFT
            state == STATE_OPEN -> PR_OPEN
            merged -> PR_MERGED
            state == STATE_CLOSED -> PR_CLOSED_UNMERGED
            else -> UNKNOWN
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

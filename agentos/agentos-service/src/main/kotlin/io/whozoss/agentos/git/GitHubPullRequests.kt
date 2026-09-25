package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.git.core.GitHubRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.net.http.HttpClient
import java.time.Duration

interface GitHostingProvider {
    fun inspect(settings: GitRepositorySettings, branch: String, headSha: String? = null): GitWorkspaceSummary
}

/** GitHub.com adapter. Other Git hosts remain usable without a PR status projection. */
@Component
class GitHubPullRequests internal constructor(
    private val accounts: GitServiceAccountResolver,
    mapper: ObjectMapper,
    client: HttpClient,
) : GitHostingProvider {
    @Autowired
    constructor(accounts: GitServiceAccountResolver, mapper: ObjectMapper) : this(
        accounts, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    )

    private val api = GitHubApi(client, mapper)

    override fun inspect(settings: GitRepositorySettings, branch: String, headSha: String?): GitWorkspaceSummary {
        val repository = requireNotNull(GitHubRepository.fromRemoteUrl(settings.repositoryUrl)) {
            "Automatic PR status is currently supported for github.com repositories"
        }
        val fullName = repository.fullName
        val head = URLEncoder.encode("${repository.owner}:$branch", Charsets.UTF_8)
        val array = request(settings, "$fullName/pulls?state=all&head=$head&sort=updated&direction=desc&per_page=100")
        val matching = array.filter {
            it.path("head").path("ref").asText() == branch &&
                it.path("head").path("repo").path("full_name").asText().equals(fullName, true) &&
                it.path("base").path("repo").path("full_name").asText().equals(fullName, true)
        }
        val open = matching.filter { it.path("state").asText() == "open" }
        check(open.size <= 1) { "Several open PRs reference this branch" }
        val pr = open.firstOrNull() ?: matching.firstOrNull()
            ?: findByHead(settings, fullName, branch, headSha)
            ?: return GitWorkspaceSummary(prState = "NONE")
        return summary(pr)
    }

    private fun findByHead(settings: GitRepositorySettings, fullName: String, branch: String, headSha: String?): JsonNode? {
        // An agent may check out an existing PR under a local alias. GitHub's commit endpoint
        // also returns PRs that merely contain this commit: only an exact, unique PR head is
        // evidence of the association. Never attribute the latest merged PR to the main branch.
        if (headSha == null || branch == settings.mainBranch) return null
        require(headSha.matches(Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})"))) { "Invalid Git commit SHA" }
        val candidates = request(settings, "$fullName/commits/${headSha.lowercase()}/pulls?per_page=100").filter {
            it.path("base").path("repo").path("full_name").asText().equals(fullName, true) &&
                it.path("head").path("sha").asText().equals(headSha, true)
        }
        check(candidates.size <= 1) { "Several PRs have the current commit as their head; cannot determine its association" }
        return candidates.singleOrNull()
    }

    private fun request(settings: GitRepositorySettings, repositoryPath: String): JsonNode {
        // The API host is fixed; repository configuration cannot redirect service credentials.
        val response = api.get("repos/$repositoryPath", accounts.resolve(settings).secret)
        check(response.status == 200) { "PR status unavailable (HTTP ${response.status})" }
        val array = response.body
        check(array != null && array.isArray && array.size() < 100) { "PR result is incomplete; cannot determine its status" }
        return array
    }

    private fun summary(pr: JsonNode): GitWorkspaceSummary {
        val state = when {
            pr.path("state").asText() == "open" && pr.path("draft").asBoolean() -> "DRAFT"
            pr.path("state").asText() == "open" -> "OPEN"
            !pr.path("merged_at").isNull && !pr.path("merged_at").isMissingNode -> "MERGED"
            pr.path("state").asText() == "closed" -> "CLOSED_UNMERGED"
            else -> "UNKNOWN"
        }
        return GitWorkspaceSummary(prState = state, prNumber = pr.path("number").asInt(), prUrl = pr.path("html_url").asText(), prHeadSha = pr.path("head").path("sha").asText())
    }
}

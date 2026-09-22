package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

interface GitHostingProvider {
    fun inspect(settings: GitRepositorySettings, branch: String, headSha: String? = null): GitWorkspaceSummary
}

/** GitHub.com adapter. Other Git hosts remain usable without a PR status projection. */
@Component
class GitHubPullRequests internal constructor(
    private val accounts: GitServiceAccountResolver,
    private val mapper: ObjectMapper,
    private val client: HttpClient,
) : GitHostingProvider {
    @Autowired
    constructor(accounts: GitServiceAccountResolver, mapper: ObjectMapper) : this(
        accounts, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    )

    override fun inspect(settings: GitRepositorySettings, branch: String, headSha: String?): GitWorkspaceSummary {
        val remote = URI(settings.repositoryUrl)
        require(remote.scheme == "https" && remote.host.equals("github.com", true)) {
            "Automatic PR status is currently supported for github.com repositories"
        }
        val parts = remote.path.trim('/').removeSuffix(".git").split('/')
        require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) { "Invalid GitHub repository" }
        val fullName = parts.joinToString("/")
        val head = URLEncoder.encode("${parts[0]}:$branch", Charsets.UTF_8)
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
        val request = HttpRequest.newBuilder(URI("https://api.github.com/repos/$repositoryPath"))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer ${accounts.resolve(settings).secret}")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "PR status unavailable (HTTP ${response.statusCode()})" }
        val array = mapper.readTree(response.body())
        check(array.isArray && array.size() < 100) { "PR result is incomplete; cannot determine its status" }
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

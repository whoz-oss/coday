package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

class GitHubPullRequestsSpec : StringSpec({
    val mapper = jacksonObjectMapper().findAndRegisterModules()
    val headSha = "a".repeat(40)
    val otherSha = "b".repeat(40)
    val settings = GitRepositorySettings(
        configId = UUID.randomUUID(), namespaceId = UUID.randomUUID(),
        repositoryUrl = "https://github.com/whoz-oss/coday.git", mainBranch = "master",
        serviceAuthSettingId = UUID.randomUUID(), autoWorktreeForRootCases = true, setupCommand = null,
    )

    fun pr(
        number: Int = 1301,
        branch: String = "feature/original-name",
        sha: String = headSha,
        state: String = "open",
        draft: Boolean = false,
        merged: Boolean = false,
        headRepo: String = "whoz-oss/coday",
        baseRepo: String = "whoz-oss/coday",
    ): Map<String, Any?> = mapOf(
        "number" to number, "html_url" to "https://github.com/whoz-oss/coday/pull/$number",
        "state" to state, "draft" to draft, "merged_at" to if (merged) "2026-09-22T10:00:00Z" else null,
        "head" to mapOf("ref" to branch, "sha" to sha, "repo" to mapOf("full_name" to headRepo)),
        "base" to mapOf("repo" to mapOf("full_name" to baseRepo)),
    )

    data class Fixture(val provider: GitHubPullRequests, val requests: MutableList<HttpRequest>)

    fun fixture(vararg responses: Pair<Int, String>): Fixture {
        val requests = mutableListOf<HttpRequest>()
        val client = mockk<HttpClient>()
        val accounts = mockk<GitServiceAccountResolver> {
            every { resolve(settings) } returns GitCredentials.UsernamePassword("service-account", "test-token")
        }
        every { client.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } answers {
            requests.add(firstArg())
            check(requests.size <= responses.size) { "Unexpected additional GitHub request" }
            val (status, body) = responses[requests.lastIndex]
            mockk<HttpResponse<String>> {
                every { statusCode() } returns status
                every { body() } returns body
            }
        }
        return Fixture(GitHubPullRequests(accounts, mapper, client), requests)
    }

    fun response(vararg prs: Map<String, Any?>): Pair<Int, String> = 200 to mapper.writeValueAsString(prs)

    "a local checkout alias resolves the PR head rather than another PR containing the commit" {
        val f = fixture(response(), response(pr(), pr(number = 1358, sha = otherSha)))
        val result = f.provider.inspect(settings, "pr-1301", headSha)
        result.prNumber shouldBe 1301
        result.prState shouldBe "OPEN"
        result.prHeadSha shouldBe headSha
        result.prUrl shouldBe "https://github.com/whoz-oss/coday/pull/1301"
        f.requests.map { it.uri().toString() } shouldBe listOf(
            "https://api.github.com/repos/whoz-oss/coday/pulls?state=all&head=whoz-oss%3Apr-1301&sort=updated&direction=desc&per_page=100",
            "https://api.github.com/repos/whoz-oss/coday/commits/$headSha/pulls?per_page=100",
        )
        f.requests.forEach {
            it.method() shouldBe "GET"
            it.headers().firstValue("Authorization").orElseThrow() shouldBe "Bearer test-token"
        }
    }

    "the exact PR head may come from a fork of the configured repository" {
        val f = fixture(response(), response(pr(headRepo = "contributor/coday")))
        f.provider.inspect(settings, "local-review", headSha).prNumber shouldBe 1301
    }

    "a PR targeting a different repository is not associated" {
        val f = fixture(response(), response(pr(baseRepo = "other/coday")))
        f.provider.inspect(settings, "local-review", headSha).prState shouldBe "NONE"
    }

    "a PR merely containing the commit is not associated" {
        val f = fixture(response(), response(pr(sha = otherSha)))
        f.provider.inspect(settings, "local-review", headSha).prState shouldBe "NONE"
    }

    "multiple exact heads are ambiguous even when only one PR remains open" {
        val f = fixture(response(), response(pr(), pr(number = 1400, state = "closed", merged = true)))
        shouldThrow<IllegalStateException> { f.provider.inspect(settings, "local-review", headSha) }
            .message shouldContain "Several PRs"
    }

    "the namespace main branch does not fall back to the latest merged PR" {
        val f = fixture(response())
        f.provider.inspect(settings, settings.mainBranch, headSha).prState shouldBe "NONE"
        f.requests.size shouldBe 1
    }

    "an absent commit does not trigger a fallback lookup" {
        val f = fixture(response())
        f.provider.inspect(settings, "local-review").prState shouldBe "NONE"
        f.requests.size shouldBe 1
    }

    "invalid commit input never becomes a GitHub API path" {
        val f = fixture(response())
        shouldThrow<IllegalArgumentException> { f.provider.inspect(settings, "local-review", "../pulls/1301") }
        f.requests.size shouldBe 1
    }

    "SHA-256 commit identifiers are accepted and normalized" {
        val sha = "A".repeat(64)
        val f = fixture(response(), response(pr(sha = sha.lowercase())))
        f.provider.inspect(settings, "local-review", sha).prNumber shouldBe 1301
        f.requests.last().uri().path shouldBe "/repos/whoz-oss/coday/commits/${sha.lowercase()}/pulls"
    }

    listOf(
        Triple("open", false, "OPEN"), Triple("open", true, "DRAFT"),
        Triple("closed", false, "CLOSED_UNMERGED"), Triple("merged", false, "MERGED"),
    ).forEach { (state, draft, expected) ->
        "an existing named branch retains $expected status without a fallback request" {
            val f = fixture(response(pr(
                state = if (state == "merged") "closed" else state,
                draft = draft, merged = state == "merged",
            )))
            f.provider.inspect(settings, "feature/original-name", otherSha).prState shouldBe expected
            f.requests.size shouldBe 1
        }
    }

    "the open named PR takes precedence over an older closed PR" {
        val f = fixture(response(pr(number = 1000, state = "closed"), pr()))
        f.provider.inspect(settings, "feature/original-name", headSha).prNumber shouldBe 1301
    }

    "multiple open named PRs remain ambiguous" {
        val f = fixture(response(pr(number = 1000), pr()))
        shouldThrow<IllegalStateException> { f.provider.inspect(settings, "feature/original-name", headSha) }
    }

    "a GitHub failure is propagated instead of reporting that no PR exists" {
        val f = fixture(response(), 403 to "{\"message\":\"Forbidden\"}")
        shouldThrow<IllegalStateException> { f.provider.inspect(settings, "local-review", headSha) }
            .message shouldContain "HTTP 403"
    }

    "an incomplete fallback page is refused even with one exact match on that page" {
        val prs = List(100) { pr(number = it + 1, sha = if (it == 0) headSha else otherSha) }
        val f = fixture(response(), 200 to mapper.writeValueAsString(prs))
        shouldThrow<IllegalStateException> { f.provider.inspect(settings, "local-review", headSha) }
            .message shouldContain "incomplete"
    }

    "an incomplete named page is refused before attempting a fallback" {
        val f = fixture(200 to mapper.writeValueAsString(List(100) { pr(number = it + 1) }))
        shouldThrow<IllegalStateException> { f.provider.inspect(settings, "local-review", headSha) }
        f.requests.size shouldBe 1
    }

    "a non-array GitHub response is refused" {
        val f = fixture(response(), 200 to "{}")
        shouldThrow<IllegalStateException> { f.provider.inspect(settings, "local-review", headSha) }
    }

    "a non-GitHub repository cannot redirect the service account token" {
        val f = fixture()
        shouldThrow<IllegalArgumentException> {
            f.provider.inspect(settings.copy(repositoryUrl = "https://attacker.example/whoz-oss/coday"), "local-review", headSha)
        }
        f.requests.size shouldBe 0
    }
})

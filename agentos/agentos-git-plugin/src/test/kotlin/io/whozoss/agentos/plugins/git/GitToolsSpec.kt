package io.whozoss.agentos.plugins.git

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Real Git in the layout the service manages: a bare repository and a detached linked worktree,
 * pinned by its administrative directory. A loopback server stands in for api.github.com.
 */
class GitToolsSpec :
    StringSpec({
        timeout = 120_000
        val runner = GitCommandRunner(
            GitExecutionProperties(allowedRemoteProtocols = setOf("file"), defaultTimeout = Duration.ofSeconds(20)),
        )
        val toolContext = ToolContext(namespaceId = UUID.randomUUID(), userId = UUID.randomUUID(), userExternalId = "dev@example.com", caseEvents = emptyList())

        fun git(directory: Path, vararg args: String): String {
            val process = ProcessBuilder(listOf("git", *args)).directory(directory.toFile()).redirectErrorStream(true).also {
                it.environment().clear()
                it.environment()["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
                it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                it.environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                it.environment()["GIT_TERMINAL_PROMPT"] = "0"
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
            return output.trim()
        }

        // --- A fake GitHub API -------------------------------------------------------------------

        data class Call(val method: String, val path: String, val authorization: String?, val body: String)

        val calls = CopyOnWriteArrayList<Call>()
        var answer: (Call) -> Pair<Int, String> = { 404 to "" }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val call = Call(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestBody.readBytes().decodeToString(),
                )
                calls.add(call)
                val (status, body) = answer(call)
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val gitHub = GitHubApi(mapper = ObjectMapper(), apiRoot = URI("http://127.0.0.1:${server.address.port}/"))

        beforeTest {
            calls.clear()
            answer = { 404 to "" }
        }
        afterSpec { server.stop(0) }

        // --- Managed layout ----------------------------------------------------------------------

        class Fixture(val origin: Path, val worktree: Path, val common: Path, val context: GitWorkspaceContext) {
            val workspace get() = GitWorkspace(context, runner)
        }

        fun managed(repositoryUrl: String? = null): Fixture {
            val root = Files.createTempDirectory("agentos-git-tools-")
            val seed = Files.createDirectories(root.resolve("seed"))
            git(seed, "init", "--quiet", "--initial-branch=main")
            seed.resolve("README.md").writeText("initial\n")
            git(seed, "add", ".")
            git(seed, "-c", "user.name=CI", "-c", "user.email=ci@example.com", "commit", "--quiet", "-m", "initial")
            val origin = root.resolve("origin.git")
            git(root, "clone", "--quiet", "--bare", seed.toString(), origin.toString())
            val common = root.resolve("repository.git")
            git(root, "clone", "--quiet", "--bare", origin.toString(), common.toString())
            val worktree = root.resolve("case").resolve("repo")
            git(common, "worktree", "add", "--quiet", "--detach", worktree.toString(), "main")
            val context = GitWorkspaceContext(
                workingDirectory = worktree,
                gitDir = common.resolve("worktrees").resolve("repo"),
                commonGitDir = common,
                repositoryUrl = repositoryUrl ?: origin.toUri().toString(),
                mainBranch = "main",
            )
            return Fixture(origin, worktree, common, context)
        }

        fun token(value: String = "synthetic-user-token") = Credential(
            userId = UUID.randomUUID(),
            authSettingId = UUID.randomUUID(),
            credentialType = CredentialType.BEARER_TOKEN,
            data = mapOf("token" to value),
        )

        fun access(credential: Credential? = token()) = GitForgeAccess(credential?.let { { it } }, "dev@example.com", gitHub)

        fun tools(fixture: Fixture, access: GitForgeAccess = access()): Map<String, StandardTool<*>> =
            gitTools("git", fixture.workspace, access, gitHub).associateBy { it.name.removePrefix("git__") }

        @Suppress("UNCHECKED_CAST")
        suspend fun Map<String, StandardTool<*>>.call(tool: String, input: Any? = null): ToolExecutionResult =
            (getValue(tool) as StandardTool<Any>).execute(input, toolContext)

        // --- Tests ---------------------------------------------------------------------------------

        "the provider gives Git tools only inside a Git workspace, named after the integration" {
            val fixture = managed()
            val mapper = jacksonObjectMapper()
            val config = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                mapOf(
                    "workingDirectory" to fixture.worktree.toString(),
                    "gitDir" to fixture.context.gitDir.toString(),
                    "commonGitDir" to fixture.common.toString(),
                    "repositoryUrl" to fixture.context.repositoryUrl,
                    "mainBranch" to "main",
                ),
            )

            GitToolProvider().provideTools(config, "company-git", toolContext).map { it.name } shouldContainExactly listOf(
                "company-git__git_status",
                "company-git__git_create_branch",
                "company-git__git_commit",
                "company-git__git_fetch",
                "company-git__git_push",
                "company-git__git_create_pull_request",
            )
            GitToolProvider().provideTools(mapper.createObjectNode(), "company-git", toolContext) shouldBe emptyList()
        }

        "status reports the detached worktree and its changes" {
            val fixture = managed()
            fixture.worktree.resolve("notes.md").writeText("draft\n")

            val status = tools(fixture).call("git_status")

            status.success shouldBe true
            status.output shouldContain "HEAD is detached"
            status.output shouldContain "?? notes.md"
        }

        "a branch is created at the current commit and checked out" {
            val fixture = managed()
            val tools = tools(fixture)

            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/exports")).success shouldBe true

            git(fixture.worktree, "symbolic-ref", "--short", "HEAD") shouldBe "feature/exports"
            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/exports")).output shouldContain "already exists"
            tools.call("git_create_branch", GitCreateBranchTool.Input("-f")).output shouldContain "not a valid branch name"
        }

        "commits are refused on a detached HEAD and authored as the user running the case" {
            val fixture = managed()
            val tools = tools(fixture)
            fixture.worktree.resolve("fix.txt").writeText("fix\n")

            tools.call("git_commit", GitCommitTool.Input("Fix")).output shouldContain "HEAD is detached"

            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/fix"))
            val result = tools.call("git_commit", GitCommitTool.Input("Fix exports"))

            result.success shouldBe true
            git(fixture.worktree, "log", "-1", "--format=%an <%ae>|%s") shouldBe "dev@example.com <dev@example.com>|Fix exports"
            tools.call("git_commit", GitCommitTool.Input("Again")).output shouldContain "Nothing to commit"
        }

        "commits refuse executable filters and never run repository hooks" {
            val fixture = managed()
            val tools = tools(fixture)
            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/guarded"))
            val marker = fixture.worktree.parent.resolve("fired")
            fixture.worktree.resolve(".gitattributes").writeText("*.txt filter=planted\n")
            fixture.worktree.resolve("a.txt").writeText("a\n")
            git(fixture.common, "config", "filter.planted.clean", "touch '$marker'; cat")

            val refused = tools.call("git_commit", GitCommitTool.Input("Filtered"))

            refused.success shouldBe false
            refused.output shouldContain "executable filters"
            marker.exists() shouldBe false

            git(fixture.common, "config", "--unset", "filter.planted.clean")
            val hooks = Files.createDirectories(fixture.worktree.parent.resolve("hooks"))
            hooks.resolve("pre-commit").writeText("#!/bin/sh\ntouch '$marker'\n")
            hooks.resolve("pre-commit").toFile().setExecutable(true)
            git(fixture.common, "config", "core.hooksPath", hooks.toString())

            tools.call("git_commit", GitCommitTool.Input("Hooked")).success shouldBe true
            marker.exists() shouldBe false
        }

        "a signing program planted in the repository configuration never runs" {
            val fixture = managed()
            val tools = tools(fixture)
            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/signed"))
            val marker = fixture.worktree.parent.resolve("signed")
            val program = fixture.worktree.parent.resolve("fake-gpg")
            program.writeText("#!/bin/sh\ntouch '$marker'\nexit 1\n")
            program.toFile().setExecutable(true)
            git(fixture.common, "config", "commit.gpgsign", "true")
            git(fixture.common, "config", "gpg.program", program.toString())
            fixture.worktree.resolve("signed.txt").writeText("signed\n")

            tools.call("git_commit", GitCommitTool.Input("Unsigned")).success shouldBe true
            marker.exists() shouldBe false
        }

        "the current branch is pushed with the user's credentials, never the main branch" {
            val fixture = managed()
            val tools = tools(fixture)
            tools.call("git_push").output shouldContain "HEAD is detached"
            git(fixture.worktree, "switch", "--quiet", "main")
            tools.call("git_push").output shouldContain "never pushed"

            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/push"))
            fixture.worktree.resolve("push.txt").writeText("push\n")
            tools.call("git_commit", GitCommitTool.Input("Push me"))
            tools(fixture, access(credential = null)).call("git_push").output shouldContain "No Git credentials"

            val pushed = tools.call("git_push")

            pushed.success shouldBe true
            git(fixture.origin, "rev-parse", "refs/heads/feature/push") shouldBe git(fixture.worktree, "rev-parse", "HEAD")
            tools.call("git_status").output shouldContain "matches the last pushed"
        }

        "a rewritten branch replaces the remote branch only with a lease on what was last seen" {
            val fixture = managed()
            val tools = tools(fixture)
            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/rebase"))
            fixture.worktree.resolve("one.txt").writeText("one\n")
            tools.call("git_commit", GitCommitTool.Input("One"))
            tools.call("git_push").success shouldBe true
            git(fixture.worktree, "-c", "user.name=CI", "-c", "user.email=ci@example.com", "commit", "--quiet", "--amend", "-m", "One, reworded")

            tools.call("git_push").success shouldBe false
            tools.call("git_push", GitPushTool.Input(forceWithLease = true)).success shouldBe true

            git(fixture.origin, "rev-parse", "refs/heads/feature/rebase") shouldBe git(fixture.worktree, "rev-parse", "HEAD")
        }

        "fetch refreshes a remote-tracking branch without touching local work" {
            val fixture = managed()
            val clone = Files.createTempDirectory("agentos-git-tools-other-")
            git(clone, "clone", "--quiet", fixture.origin.toString(), ".")
            git(clone, "-c", "user.name=Other", "-c", "user.email=other@example.com", "commit", "--quiet", "--allow-empty", "-m", "upstream")
            git(clone, "push", "--quiet", "origin", "main")
            val local = git(fixture.worktree, "rev-parse", "HEAD")

            tools(fixture).call("git_fetch").success shouldBe true

            git(fixture.worktree, "rev-parse", "refs/remotes/origin/main") shouldBe git(fixture.origin, "rev-parse", "refs/heads/main")
            git(fixture.worktree, "rev-parse", "HEAD") shouldBe local
        }

        "on GitHub a pushed branch opens a pull request with the user's account" {
            val fixture = managed(repositoryUrl = "https://github.com/whoz-oss/coday.git")
            val tools = tools(fixture)
            answer = { call ->
                when {
                    call.path == "/user" -> 200 to """{"login":"octocat","id":42,"name":"Octo Cat"}"""
                    call.method == "POST" -> 201 to """{"number":7,"html_url":"https://github.com/whoz-oss/coday/pull/7"}"""
                    else -> 404 to ""
                }
            }
            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/pr"))
            fixture.worktree.resolve("pr.txt").writeText("pr\n")
            tools.call("git_commit", GitCommitTool.Input("Open a PR")).success shouldBe true
            git(fixture.worktree, "log", "-1", "--format=%an <%ae>") shouldBe "Octo Cat <42+octocat@users.noreply.github.com>"

            tools.call("git_create_pull_request", GitCreatePullRequestTool.Input(title = "Fix")).output shouldContain "Push 'feature/pr'"
            // The network push itself is covered above; here the remote-tracking ref records it.
            git(fixture.common, "update-ref", "refs/remotes/origin/feature/pr", git(fixture.worktree, "rev-parse", "HEAD"))

            val opened = tools.call("git_create_pull_request", GitCreatePullRequestTool.Input(title = "Fix", body = "Details", draft = true))

            opened.success shouldBe true
            opened.output shouldContain "#7"
            val post = calls.single { it.method == "POST" }
            post.path shouldBe "/repos/whoz-oss/coday/pulls"
            post.authorization shouldBe "Bearer synthetic-user-token"
            jacksonObjectMapper().readTree(post.body) shouldBe jacksonObjectMapper().readTree(
                """{"title":"Fix","head":"feature/pr","base":"main","body":"Details","draft":true}""",
            )
        }

        "GitHub refusals reach the agent without the token" {
            val fixture = managed(repositoryUrl = "https://github.com/whoz-oss/coday.git")
            val tools = tools(fixture)
            answer = { call ->
                when {
                    call.path == "/user" -> 200 to """{"login":"octocat","id":42}"""
                    call.method == "POST" -> 422 to """{"message":"Validation Failed","errors":[{"message":"A pull request already exists for whoz-oss:feature/dup."}]}"""
                    else -> 404 to ""
                }
            }
            tools.call("git_create_branch", GitCreateBranchTool.Input("feature/dup"))
            fixture.worktree.resolve("dup.txt").writeText("dup\n")
            tools.call("git_commit", GitCommitTool.Input("Dup"))
            git(fixture.common, "update-ref", "refs/remotes/origin/feature/dup", git(fixture.worktree, "rev-parse", "HEAD"))

            val refused = tools.call("git_create_pull_request", GitCreatePullRequestTool.Input(title = "Dup"))

            refused.success shouldBe false
            refused.output shouldContain "A pull request already exists"
            refused.output shouldNotContain "synthetic-user-token"
        }

        "pull requests are refused for repositories outside github.com" {
            val fixture = managed()
            tools(fixture).call("git_create_pull_request", GitCreatePullRequestTool.Input(title = "Fix")).output shouldContain
                "github.com repositories"
        }
    })

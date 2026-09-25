package io.whozoss.agentos.git.core

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Real Git plus loopback-only servers and synthetic credentials. No external credentials or hosts. */
class GitNetworkCommandsSpec :
    StringSpec({
        timeout = 120_000
        val properties = GitExecutionProperties(
            allowedRemoteProtocols = setOf("http", "file"),
            allowPrivateRemoteHosts = true,
            defaultTimeout = Duration.ofSeconds(10),
        )
        val runner = GitCommandRunner(properties)
        val credentials = GitCredentials.UsernamePassword("synthetic-user", "synthetic-test-token")

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

        fun repository(): Path {
            val root = Files.createTempDirectory("agentos-network-spec-")
            git(root, "init", "--quiet", "--initial-branch=main")
            git(root, "config", "user.name", "CI")
            git(root, "config", "user.email", "ci@example.com")
            git(root, "config", "commit.gpgsign", "false")
            root.resolve("README.md").writeText("initial\n")
            git(root, "add", ".")
            git(root, "commit", "--quiet", "-m", "initial")
            git(root, "update-server-info")
            return root
        }

        "network config cannot redirect credentials or run an included credential helper" {
            val origin = repository()
            val local = repository()
            val marker = local.resolve("helper-fired")
            val attacker = GitHttpFixture(origin.resolve(".git"))
            val legitimate = GitHttpFixture(origin.resolve(".git"))
            try {
                val included = local.resolve("poison.config")
                git(local, "config", "--file", included.toString(), "url.${attacker.url}.insteadOf", legitimate.url)
                git(local, "config", "--file", included.toString(), "credential.helper", "!f() { touch '$marker'; echo username=stolen; }; f")
                git(local, "config", "include.path", included.toString())
                git(local, "config", "http.extraHeader", "X-Poison: from-local-config")
                // Control: ordinary Git obeys the included redirect and sends the synthetic header there.
                git(local, "-c", "http.extraHeader=Authorization: ${attacker.authorization}", "ls-remote", legitimate.url)
                attacker.authorizedRequests.isNotEmpty() shouldBe true
                attacker.authorizedRequests.clear()
                Files.deleteIfExists(marker)

                val refs = runner.runOrThrow(GitInvocation(
                    listOf("ls-remote", legitimate.url, "refs/heads/main"),
                    gitDir = local.resolve(".git"),
                    credentials = credentials,
                ))
                refs.substringBefore('\t') shouldBe git(origin, "rev-parse", "HEAD")
                legitimate.authorizedRequests.isNotEmpty() shouldBe true
                legitimate.poisonedRequests.isEmpty() shouldBe true
                attacker.authorizedRequests.isEmpty() shouldBe true
                marker.exists() shouldBe false

                runner.runOrThrow(GitInvocation(
                    listOf("fetch", "--quiet", legitimate.url, "+refs/heads/main:refs/remotes/origin/main"),
                    gitDir = local.resolve(".git"),
                    credentials = credentials,
                ))
                git(local, "show", "refs/remotes/origin/main:README.md") shouldBe "initial"
                val cloned = local.resolve("clone.git")
                runner.runOrThrow(GitInvocation(
                    listOf("clone", "--bare", "--", legitimate.url, cloned.toString()),
                    workingDirectory = local,
                    credentials = credentials,
                ))
                git(cloned, "show", "HEAD:README.md") shouldBe "initial"
                legitimate.poisonedRequests.isEmpty() shouldBe true
                attacker.authorizedRequests.isEmpty() shouldBe true
                marker.exists() shouldBe false
            } finally {
                attacker.close()
                legitimate.close()
            }
        }

        "HTTP resolution settings from shared config cannot capture a credential" {
            val origin = repository()
            val local = repository()
            val attacker = GitHttpFixture(origin.resolve(".git"))
            try {
                val fakeUrl = attacker.url.replace("127.0.0.1", "synthetic.invalid")
                val port = java.net.URI(attacker.url).port
                git(local, "config", "http.curloptResolve", "synthetic.invalid:$port:127.0.0.1")
                git(local, "config", "http.sslVerify", "false")
                git(local, "-c", "http.extraHeader=Authorization: ${attacker.authorization}", "ls-remote", fakeUrl)
                attacker.authorizedRequests.isNotEmpty() shouldBe true
                attacker.authorizedRequests.clear()
                shouldThrow<GitCommandException> {
                    runner.runOrThrow(GitInvocation(listOf("ls-remote", fakeUrl), gitDir = local.resolve(".git"), credentials = credentials))
                }
                attacker.authorizedRequests.isEmpty() shouldBe true
            } finally {
                attacker.close()
            }
        }

        "a credential helper in shared config runs for ordinary Git but not managed Git" {
            val origin = repository()
            val local = repository()
            val marker = local.resolve("helper-fired")
            val remote = GitHttpFixture(origin.resolve(".git"))
            try {
                git(local, "config", "credential.helper", "!f() { touch '$marker'; echo username=synthetic-user; echo password=synthetic-test-token; }; f")
                git(local, "ls-remote", remote.url)
                marker.exists() shouldBe true
                Files.delete(marker)
                runner.runOrThrow(GitInvocation(listOf("ls-remote", remote.url), gitDir = local.resolve(".git"), credentials = credentials))
                marker.exists() shouldBe false
            } finally {
                remote.close()
            }
        }

        "HTTP redirects are refused before any request reaches the redirected endpoint" {
            val origin = repository()
            val target = GitHttpFixture(origin.resolve(".git"))
            val redirect = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            redirect.createContext("/") { exchange ->
                exchange.responseHeaders.add("Location", target.url + "/info/refs")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            redirect.start()
            try {
                val url = "http://127.0.0.1:${redirect.address.port}/repo.git"
                shouldThrow<GitCommandException> {
                    runner.runOrThrow(GitInvocation(listOf("ls-remote", url), credentials = credentials))
                }
                target.requests.isEmpty() shouldBe true
            } finally {
                redirect.stop(0)
                target.close()
            }
        }

        "noncanonical loopback addresses never reach Git transport" {
            val origin = repository()
            val target = GitHttpFixture(origin.resolve(".git"))
            try {
                val restricted = GitCommandRunner(properties.copy(allowPrivateRemoteHosts = false))
                listOf("127.0.0.1", "0177.0.0.1", "0x7f000001").forEach { host ->
                    shouldThrow<GitCommandException> {
                        restricted.runOrThrow(GitInvocation(listOf("ls-remote", target.url.replace("127.0.0.1", host))))
                    }
                }
                target.requests.isEmpty() shouldBe true
                runner.runOrThrow(GitInvocation(listOf("ls-remote", target.url), credentials = credentials))
                target.authorizedRequests.isNotEmpty() shouldBe true
            } finally {
                target.close()
            }
        }

        "a new case fetch negotiates already present history when its remote advances" {
            val origin = repository()
            val local = repository()
            val trace = local.resolve("packet-trace")
            val wrapper = local.resolve("traced-git")
            wrapper.writeText("#!/bin/sh\nGIT_TRACE_PACKET='$trace' exec git \"${'$'}@\"\n")
            wrapper.toFile().setExecutable(true)
            val traced = GitCommandRunner(properties.copy(binary = wrapper.toString()))
            fun fetch(case: String) = traced.runOrThrow(GitInvocation(
                listOf("fetch", origin.toUri().toString(), "+refs/heads/main:refs/agentos/base/$case"),
                gitDir = local.resolve(".git"),
            ))
            fetch("first")
            val previous = git(origin, "rev-parse", "HEAD")
            trace.writeText("")
            origin.resolve("README.md").writeText("advanced remote history\n")
            git(origin, "commit", "--quiet", "-am", "advance")
            fetch("second")
            trace.readText().contains("have $previous") shouldBe true
            git(local, "show", "refs/agentos/base/second:README.md") shouldBe "advanced remote history"
            git(local, "for-each-ref", "--format=%(refname)", "refs/agentos/negotiation/") shouldBe ""
        }

        "a shallow fetch made by an agent does not break the next case base fetch" {
            val origin = repository()
            val local = repository()
            val url = origin.toUri().toString()
            fun fetchBase(case: String) = runner.runOrThrow(GitInvocation(
                listOf("fetch", url, "+refs/heads/main:refs/agentos/base/$case"),
                gitDir = local.resolve(".git"),
            ))
            fetchBase("first")
            origin.resolve("README.md").writeText("m1\n")
            git(origin, "commit", "--quiet", "-am", "m1")
            git(origin, "checkout", "--quiet", "-b", "feature")
            origin.resolve("feature.txt").writeText("f1\n")
            git(origin, "add", ".")
            git(origin, "commit", "--quiet", "-m", "f1")
            git(origin, "checkout", "--quiet", "main")
            origin.resolve("README.md").writeText("m2\n")
            git(origin, "commit", "--quiet", "-am", "m2")
            // An agent's shallow fetch lands in the shared repository with its own shallow boundary.
            git(local, "fetch", "--quiet", "--depth=1", url, "+refs/heads/feature:refs/remotes/origin/feature")
            local.resolve(".git/shallow").exists() shouldBe true

            fetchBase("second")

            git(local, "show", "refs/agentos/base/second:README.md") shouldBe "m2"
        }

        "an observation fetch publishes a private ref and leaves origin tracking refs untouched" {
            val origin = repository()
            val local = repository()
            val url = origin.toUri().toString()
            runner.runOrThrow(GitInvocation(
                listOf("fetch", url, "+refs/heads/main:refs/remotes/origin/main"),
                gitDir = local.resolve(".git"),
            ))
            val tracked = git(local, "rev-parse", "refs/remotes/origin/main")
            origin.resolve("README.md").writeText("advanced\n")
            git(origin, "commit", "--quiet", "-am", "advance")
            val observed = "refs/agentos/observed/${java.util.UUID.randomUUID()}"

            runner.runOrThrow(GitInvocation(
                listOf("fetch", "--quiet", url, "+refs/heads/main:$observed"),
                gitDir = local.resolve(".git"),
            ))

            git(local, "rev-parse", observed) shouldBe git(origin, "rev-parse", "HEAD")
            git(local, "rev-parse", "refs/remotes/origin/main") shouldBe tracked
        }

        "fetch publishes usable objects and tracking refs while preserving existing local branches" {
            val origin = repository()
            val local = repository()
            val originalHead = git(local, "rev-parse", "HEAD")
            val refspec = "+refs/heads/main:refs/remotes/origin/main"
            fun fetch() = runner.runOrThrow(GitInvocation(listOf("fetch", "--quiet", origin.toUri().toString(), refspec), gitDir = local.resolve(".git")))
            fetch()
            git(local, "rev-parse", "refs/remotes/origin/main") shouldBe git(origin, "rev-parse", "HEAD")
            origin.resolve("README.md").writeText("advanced\n")
            git(origin, "commit", "--quiet", "-am", "advance")
            fetch()
            git(local, "show", "refs/remotes/origin/main:README.md") shouldBe "advanced"
            git(local, "rev-parse", "refs/heads/main") shouldBe originalHead
            git(local, "fsck", "--no-dangling") shouldBe ""
        }

        "fetch replaces a symbolic tracking ref without changing its local branch target" {
            val origin = repository()
            val local = repository()
            val originalHead = git(local, "rev-parse", "HEAD")
            git(local, "branch", "agent-work", originalHead)
            git(local, "symbolic-ref", "refs/remotes/origin/main", "refs/heads/agent-work")
            origin.resolve("README.md").writeText("advanced remote state\n")
            git(origin, "commit", "--quiet", "-am", "advance")
            git(origin, "tag", "unrequested-tag")
            val remoteHead = git(origin, "rev-parse", "HEAD")

            runner.runOrThrow(GitInvocation(
                listOf("fetch", "--quiet", origin.toUri().toString(), "+refs/heads/main:refs/remotes/origin/main"),
                gitDir = local.resolve(".git"),
            ))

            git(local, "rev-parse", "refs/remotes/origin/main") shouldBe remoteHead
            git(local, "for-each-ref", "--format=%(symref)", "refs/remotes/origin/main") shouldBe ""
            git(local, "rev-parse", "refs/heads/agent-work") shouldBe originalHead
            git(local, "rev-parse", "refs/heads/main") shouldBe originalHead
            git(local, "for-each-ref", "--format=%(refname)", "refs/tags/") shouldBe ""
        }

        "Husky configuration is tolerated and its hooks are still disabled" {
            val local = repository()
            val marker = local.resolve("hook-fired")
            val hooks = Files.createDirectories(local.resolve(".husky/_"))
            val hook = hooks.resolve("reference-transaction")
            hook.writeText("#!/bin/sh\ntouch '$marker'\n")
            hook.toFile().setExecutable(true)
            git(local, "config", "core.hooksPath", hooks.toString())
            git(local, "branch", "ordinary")
            marker.exists() shouldBe true
            Files.delete(marker)
            runner.assertNoHostileLocalConfig(local.resolve(".git"))
            runner.runOrThrow(GitInvocation(listOf("branch", "managed"), gitDir = local.resolve(".git")))
            marker.exists() shouldBe false
        }
    })

private class GitHttpFixture(private val repository: Path) : AutoCloseable {
    val authorization = "Basic " + Base64.getEncoder().encodeToString("synthetic-user:synthetic-test-token".toByteArray())
    val requests = CopyOnWriteArrayList<String>()
    val authorizedRequests = CopyOnWriteArrayList<String>()
    val poisonedRequests = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url get() = "http://127.0.0.1:${server.address.port}/repo.git"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            if (exchange.requestHeaders.getFirst("X-Poison") != null) poisonedRequests.add(path)
            if (exchange.requestHeaders.getFirst("Authorization") != authorization) {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=synthetic-fixture")
                exchange.sendResponseHeaders(401, -1)
            } else {
                authorizedRequests.add(path)
                val file = repository.resolve(path.removePrefix("/repo.git/")).normalize()
                if (file.startsWith(repository) && Files.isRegularFile(file)) {
                    val content = Files.readAllBytes(file)
                    exchange.sendResponseHeaders(200, content.size.toLong())
                    exchange.responseBody.use { it.write(content) }
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
            exchange.close()
        }
        server.start()
    }

    override fun close() = server.stop(0)
}

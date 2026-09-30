package io.whozoss.agentos.git.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/** A loopback server stands in for api.github.com. */
class GitHubApiSpec :
    StringSpec({
        data class Received(val method: String, val path: String, val headers: Map<String, String?>, val body: String)

        val received = CopyOnWriteArrayList<Received>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().decodeToString()
                received.add(
                    Received(
                        exchange.requestMethod,
                        exchange.requestURI.toString(),
                        listOf("Authorization", "Accept", "X-GitHub-Api-Version", "Content-Type")
                            .associateWith { exchange.requestHeaders.getFirst(it) },
                        body,
                    ),
                )
                val (status, answer) = when {
                    exchange.requestMethod == "POST" -> 201 to """{"number":7,"html_url":"https://github.com/o/r/pull/7"}"""
                    exchange.requestURI.path == "/user" -> 200 to """{"login":"octocat","id":1}"""
                    else -> 404 to ""
                }
                val bytes = answer.toByteArray()
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val api = GitHubApi(mapper = ObjectMapper(), apiRoot = URI("http://127.0.0.1:${server.address.port}/"))

        beforeTest { received.clear() }
        afterSpec { server.stop(0) }

        "requests carry the token as a bearer credential and the GitHub API headers" {
            val response = api.get("user", "synthetic-token")

            response.status shouldBe 200
            response.body!!.path("login").asText() shouldBe "octocat"
            received.single().headers shouldBe mapOf(
                "Authorization" to "Bearer synthetic-token",
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "Content-Type" to null,
            )
        }

        "a creation posts its payload as JSON" {
            val response = api.post("repos/o/r/pulls", "synthetic-token", mapOf("title" to "Fix", "draft" to true))

            response.status shouldBe 201
            response.body!!.path("number").asInt() shouldBe 7
            val request = received.single()
            request.method shouldBe "POST"
            request.path shouldBe "/repos/o/r/pulls"
            request.headers["Content-Type"] shouldBe "application/json"
            ObjectMapper().readTree(request.body) shouldBe ObjectMapper().readTree("""{"title":"Fix","draft":true}""")
        }

        "an empty answer keeps its status without a body" {
            val response = api.get("repos/o/r/missing", "synthetic-token")

            response.status shouldBe 404
            response.body shouldBe null
        }

        "an unusable token is refused before any request, without appearing in the error" {
            listOf("synthetic-token\n", "synthetic-tokenĀ").forEach { token ->
                val error = shouldThrow<IllegalArgumentException> { api.get("user", token) }
                error.message!! shouldNotContain "synthetic-token"
                error.cause shouldBe null
            }
            received.size shouldBe 0
        }

        "only HTTPS github.com remotes name a GitHub repository" {
            GitHubRepository.fromRemoteUrl("https://github.com/whoz-oss/coday.git") shouldBe GitHubRepository("whoz-oss", "coday")
            GitHubRepository.fromRemoteUrl("https://GitHub.com/whoz-oss/coday") shouldBe GitHubRepository("whoz-oss", "coday")
            listOf(
                "http://github.com/whoz-oss/coday.git",
                "https://gitlab.com/whoz-oss/coday.git",
                "https://github.com/whoz-oss/coday/extra.git",
                "https://github.com/whoz oss/coday.git",
                "https://github.com/",
                "not a url",
            ).forEach { GitHubRepository.fromRemoteUrl(it) shouldBe null }
        }
    })

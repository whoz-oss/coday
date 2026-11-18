package io.whozoss.agentos.git.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.net.URI

class GitHubForgeDeploymentReaderSpec : StringSpec({
    val work = "cbf17264d4dbbb58cc63cadfdfe92ea63d8c8977"
    val integrated = "1bfdb5f8dfa89880fa61675092fc7e1498a13e23"
    val tag = "integration-talent-portal_rc100"
    var compareStatus = "ahead"
    var deployConclusion = "success"
    var jobLog = "RELEASE_TAG: $tag\nTARGET_ENVIRONMENT: forge-talent-portal\n"
    val mapper = ObjectMapper()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { request ->
            val path = request.requestURI.path
            if (path.endsWith("/actions/jobs/135042982985/logs")) {
                request.responseHeaders.add("Location", "http://127.0.0.1:${request.localAddress.port}/signed-log")
                request.sendResponseHeaders(302, -1)
            } else {
                val response = when {
                    path.contains("/compare/") -> mapOf("status" to compareStatus)
                    path.endsWith("/actions/runs") -> mapOf("total_count" to 2, "workflow_runs" to listOf(
                        mapOf("id" to 44369638977L, "head_sha" to integrated,
                            "head_branch" to "integration/talent-portal", "conclusion" to "success",
                            "event" to "push", "path" to ".github/workflows/build.yml"),
                        mapOf("id" to 44371473992L, "head_sha" to integrated,
                            "head_branch" to "integration/talent-portal", "conclusion" to deployConclusion,
                            "event" to "workflow_dispatch", "path" to ".github/workflows/deploy.yml",
                            "name" to "Deploy forge-talent-portal"),
                    ))
                    path.endsWith("/actions/runs/44371473992/jobs") -> mapOf("total_count" to 1,
                        "jobs" to listOf(mapOf("id" to 135042982985L, "name" to "deploy to workstream",
                            "conclusion" to "success")))
                    path.endsWith("/git/ref/tags/$tag") -> mapOf("object" to mapOf("type" to "commit", "sha" to integrated))
                    path == "/signed-log" -> null
                    else -> error("Unexpected API path: $path")
                }
                val bytes = (response?.let(mapper::writeValueAsString) ?: jobLog).toByteArray()
                request.sendResponseHeaders(200, bytes.size.toLong())
                request.responseBody.use { it.write(bytes) }
            }
            request.close()
        }
        start()
    }
    val reader = GitHubForgeDeploymentReader(
        GitHubApi(mapper = mapper, apiRoot = URI("http://127.0.0.1:${server.address.port}/")),
    )
    fun observe() = reader.read(GitHubRepository("o", "r"), "synthetic-token", work,
        "integration/talent-portal", integrated, "forge-talent-portal")

    beforeTest {
        compareStatus = "ahead"
        deployConclusion = "success"
        jobLog = "RELEASE_TAG: $tag\nTARGET_ENVIRONMENT: forge-talent-portal\n"
    }
    afterSpec { server.stop(0) }

    "the example's work ancestry and successful job resolve to one deployed tag" {
        val evidence = observe()
        evidence?.tag shouldBe tag
        evidence?.workCommit shouldBe work
        evidence?.buildRunId shouldBe 44369638977L
        evidence?.deployJobId shouldBe 135042982985L
        reader.readLatest(GitHubRepository("o", "r"), "synthetic-token", work,
            "integration/talent-portal", "forge-talent-portal")?.integrationCommit shouldBe integrated
    }

    "a conflict, failed deployment or contradictory log keeps validation closed" {
        compareStatus = "diverged"
        observe() shouldBe null
        compareStatus = "ahead"
        deployConclusion = "failure"
        observe() shouldBe null
        deployConclusion = "success"
        jobLog += "RELEASE_TAG: integration-talent-portal_rc101\n"
        observe() shouldBe null
    }

    "the target must be the Whoz environment derived from the integration branch" {
        reader.read(GitHubRepository("o", "r"), "synthetic-token", work,
            "integration/talent-portal", integrated, "forge-other") shouldBe null
        reader.read(GitHubRepository("o", "r"), "synthetic-token", work,
            "integration/talent-portal/sprint", integrated, "forge-talent-portal") shouldBe null
    }
})

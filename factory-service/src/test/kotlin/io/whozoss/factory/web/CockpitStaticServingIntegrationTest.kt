package io.whozoss.factory.web

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.workflow.service.WorkflowService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * HTTP integration tests of the same-origin cockpit hosting.
 *
 * Verifies that `factory-service` serves the vanilla cockpit shell, its ES
 * modules with an `application/javascript` content type (a browser requirement
 * for `type="module"`), the bootstrap `/api/config` route, and that the
 * multi-lane projection (agent / code / human) is exposed over HTTP.
 */
class CockpitStaticServingIntegrationTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var workflowService: WorkflowService

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"

    @Test
    fun `serves the cockpit shell at slash cockpit`() {
        val response = restTemplate.getForEntity("/cockpit", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType).isNotNull
        assertThat(response.headers.contentType!!.isCompatibleWith(MediaType.TEXT_HTML)).isTrue()
        assertThat(response.body).contains("Coday Factory Cockpit")
    }

    @Test
    fun `redirects the root to the cockpit`() {
        val response = restTemplate.getForEntity("/", String::class.java)
        if (response.statusCode.is3xxRedirection) {
            assertThat(response.headers.location?.toString()).isEqualTo("/cockpit")
        } else {
            // TestRestTemplate may follow the redirect to the served shell.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Coday Factory Cockpit")
        }
    }

    @Test
    fun `serves es modules as application javascript`() {
        val response = restTemplate.getForEntity("/js/app.mjs", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType).isNotNull
        assertThat(response.headers.contentType!!.toString()).startsWith("application/javascript")
        assertThat(response.body).contains("bootstrapCockpit")
    }

    @Test
    fun `serves the stylesheet as text css`() {
        val response = restTemplate.getForEntity("/css/dockyard.css", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType!!.isCompatibleWith(MediaType.valueOf("text/css"))).isTrue()
    }

    @Test
    fun `exposes the cockpit bootstrap config`() {
        val response = restTemplate.getForEntity("/api/config", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("agentosUrl")
    }

    @Test
    fun `exposes the multi-lane projection of a session`() {
        val projection = linkedMapOf<String, Any?>(
            "schemaVersion" to "2",
            "workflowId" to "wf-lanes",
            "workflowType" to "wf-lanes",
            "title" to "Lanes",
            "status" to "ready",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "analyse",
                    "name" to "Analyse",
                    "status" to "completed",
                    "dependsOn" to emptyList<String>(),
                    "responsibility" to linkedMapOf("kind" to "agent", "name" to "analyst"),
                ),
                linkedMapOf(
                    "id" to "build",
                    "name" to "Build",
                    "status" to "ready",
                    "dependsOn" to listOf("analyse"),
                    "responsibility" to linkedMapOf("kind" to "code", "name" to "ci"),
                ),
                linkedMapOf(
                    "id" to "gate",
                    "name" to "Gate",
                    "status" to "pending",
                    "dependsOn" to listOf("build"),
                    "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
                ),
            ),
        )
        workflowService.publishProjection(scope, namespace, "wf-lanes", projection, null, null)

        val response = restTemplate.getForEntity(
            "/api/factory/workflows/wf-lanes/projection?namespaceId=$namespace",
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"lane\":\"agent\"")
        assertThat(response.body).contains("\"lane\":\"code\"")
        assertThat(response.body).contains("\"lane\":\"human\"")
        assertThat(response.body).contains("\"name\":\"reviewer\"")
    }
}

package io.whozoss.factory.oracle

import io.whozoss.factory.PostgresContainerSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path

/**
 * HTTP integration tests of [io.whozoss.factory.oracle.web.OracleController]
 * against a real PostgreSQL instance.
 *
 * The trust context is the loopback-dev principal granted by
 * `LocalDevMembershipResolver` (`org-local-dev` / `ws-default`). Skipped
 * gracefully when no Docker daemon is available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class OracleControllerIntegrationTest : PostgresContainerSpec() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun ensureWorkflowInstance() {
        jdbcTemplate.update(
            """
            INSERT INTO workflow_instances
                (organization_id, workstream_id, namespace_id, workflow_id, instance_json, projection_json)
            VALUES (?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb)
            ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id) DO NOTHING
            """.trimIndent(),
            ORG,
            WS,
            NAMESPACE,
            WORKFLOW,
        )
    }

    private fun postRun(
        oracleId: String,
        body: String,
        idempotencyKey: String? = null,
    ): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey)
        val url = "/api/factory/workflows/$WORKFLOW/steps/verify-code/oracles/$oracleId/runs"
        return restTemplate.exchange(
            url,
            HttpMethod.POST,
            HttpEntity(body, headers),
            object : ParameterizedTypeReference<Map<String, Any?>>() {},
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun data(body: Map<String, Any?>?): Map<String, Any?>? = body?.get("data") as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(body: Map<String, Any?>?): String? =
        (body?.get("error") as? Map<String, Any?>)?.get("code") as? String

    @Test
    fun `runs an oracle and wraps the payload in a data envelope`() {
        val response = postRun("smoke", """{"namespaceId":"$NAMESPACE"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val data = data(response.body)
        assertThat(data).isNotNull
        assertThat(data!!["workflowId"]).isEqualTo(WORKFLOW)
        assertThat(data["stepId"]).isEqualTo("verify-code")
        assertThat(data["oracleId"]).isEqualTo("smoke")
        assertThat(data["status"]).isEqualTo("SUCCEEDED")
        assertThat(data["revision"]).isEqualTo(2)
        assertThat(data["executionId"]).isNotNull
    }

    @Test
    fun `unknown oracle yields 404 ORACLE_NOT_FOUND`() {
        val response = postRun("does-not-exist", """{"namespaceId":"$NAMESPACE"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(errorCode(response.body)).isEqualTo("ORACLE_NOT_FOUND")
    }

    @Test
    fun `missing namespaceId yields 400 INVALID_ORACLE_RUN_REQUEST`() {
        val response = postRun("smoke", """{}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(errorCode(response.body)).isEqualTo("INVALID_ORACLE_RUN_REQUEST")
    }

    @Test
    fun `idempotency key replays the same execution with 200`() {
        val first = postRun("smoke", """{"namespaceId":"$NAMESPACE"}""", idempotencyKey = "controller-key-1")
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        val firstExecutionId = data(first.body)!!["executionId"]

        val second = postRun("smoke", """{"namespaceId":"$NAMESPACE"}""", idempotencyKey = "controller-key-1")
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        val secondData = data(second.body)!!
        assertThat(secondData["executionId"]).isEqualTo(firstExecutionId)
        assertThat(secondData["idempotent"]).isEqualTo(true)
    }

    companion object {
        private const val ORG = "org-local-dev"
        private const val WS = "ws-default"
        private const val NAMESPACE = "ns-controller-test"
        private const val WORKFLOW = "wf-controller-test"

        private val smokeDefinition = """
            {
              "schemaVersion": "1",
              "id": "smoke",
              "version": "1.0.0",
              "domain": "factory",
              "argv": ["node", "script.mjs"],
              "cwd": "repo-root",
              "timeoutMs": 10000,
              "success": { "rule": "exit-code", "requireWork": true },
              "applicable": { "workflowTypes": ["oracle-smoke"], "stepIds": ["verify-code"] }
            }
        """.trimIndent()

        private val oracleDefinitionsRoot: Path = Files.createTempDirectory("oracle-controller-definitions")
            .also { root -> Files.writeString(root.resolve("smoke@1.0.0.json"), smokeDefinition) }

        @JvmStatic
        @DynamicPropertySource
        fun registerOracleDefinitions(registry: DynamicPropertyRegistry) {
            registry.add("factory.oracle.definitions-root") { oracleDefinitionsRoot.toAbsolutePath().toString() }
        }
    }
}

package io.whozoss.factory.run

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.domain.WorkstreamStatus
import io.whozoss.factory.workstream.persistence.Neo4jWorkstreamRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * HTTP integration test of [io.whozoss.factory.run.web.RunController]:
 * - `POST /api/factory/runs`
 * - `GET /api/factory/runs`
 * - `GET /api/factory/runs/{runId}`
 * - `POST /api/factory/runs/{runId}/escalations/{escalationId}/reply`
 */
class RunControllerHttpTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var workstreamRepository: Neo4jWorkstreamRepository

    private val namespace = "9b2d4f60-3c4d-4e5f-8a11-ab0123456789"

    @BeforeEach
    fun seedWorkstream() {
        runCatching {
            workstreamRepository.create(
                scope,
                Workstream(
                    organizationId = scope.organizationId,
                    workstreamId = scope.workstreamId,
                    namespaceId = namespace,
                    name = "HTTP Test Workstream",
                    status = WorkstreamStatus.ACTIVE,
                ),
            )
        }
    }

    @Test
    fun `GET runs and missing run return expected HTTP envelopes`() {
        val missingRun = restTemplate.getForEntity(
            "/api/factory/runs/unknown-run?namespaceId=$namespace",
            Map::class.java,
        )
        assertThat(missingRun.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        val emptyList = restTemplate.getForEntity(
            "/api/factory/runs?namespaceId=$namespace",
            Map::class.java,
        )
        assertThat(emptyList.statusCode).isEqualTo(HttpStatus.OK)
        val data = emptyList.body?.get("data") as Map<*, *>
        assertThat(data["namespaceId"]).isEqualTo(namespace)
    }
}

package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.security.SuperAdminCallerSpec
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [CaseEventSseController]: the status the exception rendering produces.
 *
 * Refusing the stream of a case that is gone (#1616) only works through its status code —
 * EventSource reconnects every three seconds for as long as it is given a 200, whatever the
 * body — and that status comes out of exception rendering on a handler that produces nothing
 * but `text/event-stream`, which a unit test calling the method directly cannot observe.
 *
 * MockMvc stops at the first response and performs no servlet ERROR dispatch, so this pins the
 * status this handler resolves to, not what a container finally writes after /error has
 * renegotiated the content type. A running instance was checked by hand and answers 404 as well
 * (2026-11-10); a RANDOM_PORT test would be the way to hold that end of the contract too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CaseEventSseControllerMvcIntegrationSpec : SuperAdminCallerSpec() {
    @Autowired lateinit var mockMvc: MockMvc

    init {
        "GET /api/cases/{caseId}/events on a case that does not exist answers 404" {
            mockMvc
                .perform(
                    get("/api/cases/${UUID.randomUUID()}/events").accept(MediaType.TEXT_EVENT_STREAM),
                ).andExpect(status().isNotFound)
        }
    }
}

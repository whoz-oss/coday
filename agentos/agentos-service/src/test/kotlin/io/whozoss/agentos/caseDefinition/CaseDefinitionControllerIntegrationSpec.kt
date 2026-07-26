package io.whozoss.agentos.caseDefinition

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CaseDefinitionControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    private val namespaceId = UUID.randomUUID()
    private val agentConfigId = UUID.randomUUID()

    /**
     * A fully valid POST payload with nested [recurrence] and [planning] objects.
     */
    private val validPayload
        get() = """
            {
                "namespaceId": "$namespaceId",
                "agentConfigId": "$agentConfigId",
                "promptContent": "Hello from the scheduled case",
                "name": "daily-standup",
                "recurrence": {
                    "every": 1,
                    "unit": "DAY",
                    "days": [],
                    "timeUtc": "08:00"
                },
                "planning": {
                    "startDate": "2026-01-01",
                    "endType": "NEVER"
                },
                "enabled": true
            }
        """.trimIndent()

    private fun postDef(body: String = validPayload) =
        post("/api/case-definitions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    init {
        // -------------------------------------------------------------------------
        // Bean Validation — top-level required fields
        // -------------------------------------------------------------------------

        "POST with blank name returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "Hello",
                    "name": "",
                    "recurrence": {"every": 1, "unit": "DAY", "timeUtc": "08:00"},
                    "planning": {"startDate": "2026-01-01", "endType": "NEVER"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        "POST with missing agentConfigId returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "promptContent": "Hello",
                    "name": "my-def",
                    "recurrence": {"every": 1, "unit": "DAY", "timeUtc": "08:00"},
                    "planning": {"startDate": "2026-01-01", "endType": "NEVER"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        "POST with blank promptContent returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "",
                    "name": "my-def",
                    "recurrence": {"every": 1, "unit": "DAY", "timeUtc": "08:00"},
                    "planning": {"startDate": "2026-01-01", "endType": "NEVER"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        "POST with missing recurrence returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "Hello",
                    "name": "my-def",
                    "planning": {"startDate": "2026-01-01", "endType": "NEVER"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        "POST with missing planning returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "Hello",
                    "name": "my-def",
                    "recurrence": {"every": 1, "unit": "DAY", "timeUtc": "08:00"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // Bean Validation — recurrence nested fields
        // -------------------------------------------------------------------------

        "POST with every = 0 in recurrence returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "Hello",
                    "name": "my-def",
                    "recurrence": {"every": 0, "unit": "DAY", "timeUtc": "08:00"},
                    "planning": {"startDate": "2026-01-01", "endType": "NEVER"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        "POST with missing unit in recurrence returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "Hello",
                    "name": "my-def",
                    "recurrence": {"every": 1, "timeUtc": "08:00"},
                    "planning": {"startDate": "2026-01-01", "endType": "NEVER"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        "POST with invalid timeUtc format returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "Hello",
                    "name": "my-def",
                    "recurrence": {"every": 1, "unit": "DAY", "timeUtc": "9:00"},
                    "planning": {"startDate": "2026-01-01", "endType": "NEVER"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // Bean Validation — planning nested fields
        // -------------------------------------------------------------------------

        "POST with missing startDate in planning returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "Hello",
                    "name": "my-def",
                    "recurrence": {"every": 1, "unit": "DAY", "timeUtc": "08:00"},
                    "planning": {"endType": "NEVER"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        "POST with missing endType in planning returns 400" {
            mockMvc.perform(postDef(body = """
                {
                    "namespaceId": "$namespaceId",
                    "agentConfigId": "$agentConfigId",
                    "promptContent": "Hello",
                    "name": "my-def",
                    "recurrence": {"every": 1, "unit": "DAY", "timeUtc": "08:00"},
                    "planning": {"startDate": "2026-01-01"}
                }
            """.trimIndent()))
                .andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // PUT — validation
        // -------------------------------------------------------------------------

        "PUT with blank name returns 400" {
            mockMvc.perform(
                put("/api/case-definitions/${UUID.randomUUID()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "agentConfigId": "$agentConfigId",
                            "promptContent": "Hello",
                            "name": "",
                            "recurrence": {"every": 1, "unit": "DAY", "timeUtc": "08:00"},
                            "planning": {"startDate": "2026-01-01", "endType": "NEVER"}
                        }
                    """.trimIndent()),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // search endpoint
        // -------------------------------------------------------------------------

        "POST /search with valid body returns 200" {
            mockMvc.perform(
                post("/api/case-definitions/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"namespaceId": "$namespaceId"}"""),
            ).andExpect(status().isOk)
        }

        "POST /search with null scope (platform) returns 200" {
            mockMvc.perform(
                post("/api/case-definitions/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{}"""),
            ).andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // effective endpoint
        // -------------------------------------------------------------------------

        "POST /effective without namespaceId or namespaceExternalId returns 400" {
            mockMvc.perform(
                post("/api/case-definitions/effective")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId": "${UUID.randomUUID()}"}"""),
            ).andExpect(status().isBadRequest)
        }

        "POST /effective without userId or userExternalId returns 400" {
            mockMvc.perform(
                post("/api/case-definitions/effective")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"namespaceId": "$namespaceId"}"""),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // toggle and delete — 404 on unknown id
        // -------------------------------------------------------------------------

        "PATCH /toggle on non-existent id returns 404" {
            mockMvc.perform(patch("/api/case-definitions/${UUID.randomUUID()}/toggle"))
                .andExpect(status().isNotFound)
        }

        "DELETE on non-existent id returns 404" {
            mockMvc.perform(delete("/api/case-definitions/${UUID.randomUUID()}"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // by-ids
        // -------------------------------------------------------------------------

        "POST /by-ids with valid body returns 200" {
            mockMvc.perform(
                post("/api/case-definitions/by-ids")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ids": []}"""),
            ).andExpect(status().isOk)
        }
    }
}

package io.whozoss.agentos.scheduledTask

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.hamcrest.Matchers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer integration tests for [CaseDefinitionController].
 *
 * Payload shape: flat fields `frequency`, `timeUtc`, `dayOfWeek` at root level
 * (no nested `schedule` object). Mirrors exactly what the frontend sends.
 *
 * Verifies:
 * - Bean Validation fires through the dispatcher (400 on invalid payloads)
 * - HTTP status codes (201 / 200 / 204 / 400 / 404)
 * - `namespaceId` absent → 400
 * - `namespaceId` in body mismatch with query param → 400
 * - `userGroupId + userId` both set → 400
 * - WEEKLY without dayOfWeek → 400
 * - Targeting and schedule fields appear flat in the response body
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CaseDefinitionControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var caseDefinitionService: CaseDefinitionService

    private val namespaceId = UUID.randomUUID()
    private val agentId = UUID.randomUUID()

    /** Minimal valid DAILY payload — flat fields, no nested schedule object. */
    private val dailyPayload
        get() = """
            {
                "namespaceId": "$namespaceId",
                "name": "daily-standup",
                "agentId": "$agentId",
                "prompt": "Good morning!",
                "frequency": "DAILY",
                "timeUtc": "08:00",
                "enabled": true
            }
        """.trimIndent()

    private fun postDef(nsId: UUID = namespaceId, body: String = dailyPayload) =
        post("/api/case-definitions")
            .param("namespaceId", nsId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    private fun createDef(
        nsId: UUID = namespaceId,
        name: String = "test-def",
        userGroupId: UUID? = null,
        userId: UUID? = null,
        cronExpression: String = "0 8 * * *",
        enabled: Boolean = true,
    ) = caseDefinitionService.create(
        CaseDefinition(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = nsId,
            userGroupId = userGroupId,
            userId = userId,
            name = name,
            agentId = agentId,
            prompt = "Hello",
            cronExpression = cronExpression,
            enabled = enabled,
        ),
    )

    init {
        // -------------------------------------------------------------------------
        // POST — namespace-only, flat payload
        // -------------------------------------------------------------------------

        "POST DAILY namespace-only returns 201 with flat schedule fields" {
            mockMvc.perform(postDef())
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.namespaceId").value(namespaceId.toString()))
                .andExpect(jsonPath("$.userGroupId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.frequency").value("DAILY"))
                .andExpect(jsonPath("$.timeUtc").value("08:00"))
                .andExpect(jsonPath("$.dayOfWeek").doesNotExist())
        }

        // -------------------------------------------------------------------------
        // POST — WEEKLY
        // -------------------------------------------------------------------------

        "POST WEEKLY returns 201 with dayOfWeek" {
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$namespaceId",
                            "name": "weekly-review",
                            "agentId": "$agentId",
                            "prompt": "Weekly review",
                            "frequency": "WEEKLY",
                            "timeUtc": "10:00",
                            "dayOfWeek": "MON"
                        }
                    """.trimIndent(),
                ),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.timeUtc").value("10:00"))
                .andExpect(jsonPath("$.dayOfWeek").value("MON"))
        }

        // -------------------------------------------------------------------------
        // POST — group-scoped
        // -------------------------------------------------------------------------

        "POST with userGroupId returns 201 with userGroupId in response" {
            val groupId = UUID.randomUUID()
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$namespaceId",
                            "userGroupId": "$groupId",
                            "name": "group-def",
                            "agentId": "$agentId",
                            "prompt": "Hello group",
                            "frequency": "DAILY",
                            "timeUtc": "09:00"
                        }
                    """.trimIndent(),
                ),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.userGroupId").value(groupId.toString()))
                .andExpect(jsonPath("$.userId").doesNotExist())
        }

        // -------------------------------------------------------------------------
        // POST — user-scoped
        // -------------------------------------------------------------------------

        "POST with userId returns 201 with userId in response" {
            val uid = UUID.randomUUID()
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$namespaceId",
                            "userId": "$uid",
                            "name": "user-def",
                            "agentId": "$agentId",
                            "prompt": "Hello user",
                            "frequency": "DAILY",
                            "timeUtc": "09:00"
                        }
                    """.trimIndent(),
                ),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.userId").value(uid.toString()))
                .andExpect(jsonPath("$.userGroupId").doesNotExist())
        }

        // -------------------------------------------------------------------------
        // POST — invalid combinations
        // -------------------------------------------------------------------------

        "POST with userGroupId + userId returns 400" {
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$namespaceId",
                            "userGroupId": "${UUID.randomUUID()}",
                            "userId": "${UUID.randomUUID()}",
                            "name": "bad",
                            "agentId": "$agentId",
                            "prompt": "Hello",
                            "frequency": "DAILY",
                            "timeUtc": "09:00"
                        }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest)
        }

        "POST with namespaceId mismatch returns 400" {
            val otherNs = UUID.randomUUID()
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$otherNs",
                            "name": "mismatch",
                            "agentId": "$agentId",
                            "prompt": "Hello",
                            "frequency": "DAILY",
                            "timeUtc": "09:00"
                        }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // POST — Bean Validation
        // -------------------------------------------------------------------------

        "POST without namespaceId query param returns 400" {
            mockMvc.perform(
                post("/api/case-definitions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(dailyPayload),
            ).andExpect(status().isBadRequest)
        }

        "POST with blank name returns 400" {
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$namespaceId",
                            "name": "",
                            "agentId": "$agentId",
                            "prompt": "Hello",
                            "frequency": "DAILY",
                            "timeUtc": "08:00"
                        }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest)
        }

        "POST with blank prompt returns 400" {
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$namespaceId",
                            "name": "my-def",
                            "agentId": "$agentId",
                            "prompt": "",
                            "frequency": "DAILY",
                            "timeUtc": "08:00"
                        }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest)
        }

        "POST with missing frequency returns 400" {
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$namespaceId",
                            "name": "my-def",
                            "agentId": "$agentId",
                            "prompt": "Hello",
                            "timeUtc": "08:00"
                        }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest)
        }

        "POST WEEKLY without dayOfWeek returns 400" {
            mockMvc.perform(
                postDef(
                    body = """
                        {
                            "namespaceId": "$namespaceId",
                            "name": "weekly-no-day",
                            "agentId": "$agentId",
                            "prompt": "Hello",
                            "frequency": "WEEKLY",
                            "timeUtc": "09:00"
                        }
                    """.trimIndent(),
                ),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // GET — list
        // -------------------------------------------------------------------------

        "GET list returns definitions for the namespace" {
            val listNsId = UUID.randomUUID()
            createDef(nsId = listNsId, name = "def-a")
            createDef(nsId = listNsId, name = "def-b")

            mockMvc.perform(
                get("/api/case-definitions").param("namespaceId", listNsId.toString()),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", Matchers.hasSize<Any>(2)))
        }

        "GET list without namespaceId returns 400" {
            mockMvc.perform(get("/api/case-definitions"))
                .andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // GET — by id
        // -------------------------------------------------------------------------

        "GET by id returns 200 with flat schedule fields" {
            val getNsId = UUID.randomUUID()
            val groupId = UUID.randomUUID()
            val created = createDef(
                nsId = getNsId,
                name = "fetch-me",
                userGroupId = groupId,
                cronExpression = "0 10 * * MON",
            )

            mockMvc.perform(
                get("/api/case-definitions/${created.id}").param("namespaceId", getNsId.toString()),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(created.id.toString()))
                .andExpect(jsonPath("$.namespaceId").value(getNsId.toString()))
                .andExpect(jsonPath("$.userGroupId").value(groupId.toString()))
                .andExpect(jsonPath("$.frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.timeUtc").value("10:00"))
                .andExpect(jsonPath("$.dayOfWeek").value("MON"))
        }

        "GET by id returns 404 when definition does not exist" {
            mockMvc.perform(
                get("/api/case-definitions/${UUID.randomUUID()}").param("namespaceId", namespaceId.toString()),
            ).andExpect(status().isNotFound)
        }

        "GET by id without namespaceId returns 400" {
            mockMvc.perform(get("/api/case-definitions/${UUID.randomUUID()}"))
                .andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // PUT — update
        // -------------------------------------------------------------------------

        "PUT with valid payload returns 200" {
            val putNsId = UUID.randomUUID()
            val created = createDef(nsId = putNsId, name = "original")

            mockMvc.perform(
                put("/api/case-definitions/${created.id}")
                    .param("namespaceId", putNsId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$putNsId",
                            "name": "updated",
                            "agentId": "$agentId",
                            "prompt": "Updated",
                            "frequency": "WEEKLY",
                            "timeUtc": "10:00",
                            "dayOfWeek": "WED",
                            "enabled": false
                        }
                    """.trimIndent()),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.name").value("updated"))
                .andExpect(jsonPath("$.frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.dayOfWeek").value("WED"))
                .andExpect(jsonPath("$.enabled").value(false))
        }

        "PUT with blank name returns 400" {
            mockMvc.perform(
                put("/api/case-definitions/${UUID.randomUUID()}")
                    .param("namespaceId", namespaceId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "namespaceId": "$namespaceId",
                            "name": "",
                            "agentId": "$agentId",
                            "prompt": "Hi",
                            "frequency": "DAILY",
                            "timeUtc": "08:00"
                        }
                    """.trimIndent()),
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // PATCH toggle
        // -------------------------------------------------------------------------

        "PATCH toggle flips enabled state" {
            val toggleNsId = UUID.randomUUID()
            val created = createDef(nsId = toggleNsId, enabled = true)

            mockMvc.perform(
                patch("/api/case-definitions/${created.id}/toggle")
                    .param("namespaceId", toggleNsId.toString()),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.enabled").value(false))
        }

        // -------------------------------------------------------------------------
        // DELETE
        // -------------------------------------------------------------------------

        "DELETE returns 204 when definition exists" {
            val delNsId = UUID.randomUUID()
            val created = createDef(nsId = delNsId)

            mockMvc.perform(
                delete("/api/case-definitions/${created.id}")
                    .param("namespaceId", delNsId.toString()),
            ).andExpect(status().isNoContent)
        }

        "DELETE returns 404 when definition does not exist" {
            mockMvc.perform(
                delete("/api/case-definitions/${UUID.randomUUID()}")
                    .param("namespaceId", namespaceId.toString()),
            ).andExpect(status().isNotFound)
        }

        "DELETE without namespaceId returns 400" {
            mockMvc.perform(
                delete("/api/case-definitions/${UUID.randomUUID()}"),
            ).andExpect(status().isBadRequest)
        }
    }
}

package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [CaseController].
 *
 * The "test" profile uses [io.whozoss.agentos.permissions.InMemoryPermissionServiceImpl],
 * a permissive no-op stub: every `hasPermission` returns true. Negative paths
 * (403 without namespace access) are therefore covered in [CaseControllerSpec]
 * at the unit level, not here.
 *
 * This suite focuses on:
 * - Bean Validation (`namespaceId` required)
 * - Successful POST /api/cases returns 201 with correct body shape
 * - The JSON response includes the case metadata
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var namespaceService: NamespaceService

    init {

        "POST /api/cases with missing namespaceId returns 400" {
            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "title": "no namespace" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/cases with a valid namespaceId returns 201 (super-admin path)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-create"),
            )

            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "hello" }""")
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.namespaceId").value(ns.id.toString()))
                .andExpect(jsonPath("$.id").exists())
                // Title is now preserved after the CaseServiceImpl fix.
                .andExpect(jsonPath("$.title").value("hello"))
        }

        "POST /api/cases without title still returns 201 (title optional)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-no-title"),
            )

            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // GET /api/cases/by-parentId/{namespaceId}
        // -------------------------------------------------------------------------

        "GET /api/cases/by-parentId/{namespaceId} returns cases for a super-admin caller" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-list-cases"),
            )
            // Create 2 cases in the namespace via the REST API (super-admin path)
            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated)
            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated)

            mockMvc.perform(get("/api/cases/by-parentId/${ns.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(2)))
        }

        // -------------------------------------------------------------------------
        // PUT /api/cases/{id}
        // -------------------------------------------------------------------------

        "PUT /api/cases/{id} updates title when caller has WRITE permission (super-admin)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-update"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "initial" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$caseId", "namespaceId": "${ns.id}", "title": "updated" }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(caseId))
                .andExpect(jsonPath("$.title").value("updated"))
                .andExpect(jsonPath("$.namespaceId").value(ns.id.toString()))
        }

        "PUT /api/cases/{id} returns 404 when case does not exist" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-put-404"),
            )
            val missing = UUID.randomUUID()

            mockMvc.perform(
                put("/api/cases/$missing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$missing", "namespaceId": "${ns.id}", "title": "nope" }""")
            ).andExpect(status().isNotFound)
        }

        "PUT /api/cases/{id} with missing namespaceId returns 400 (Bean Validation)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-put-400"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$caseId", "title": "no namespace" }""")
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // DELETE /api/cases/{id}
        // -------------------------------------------------------------------------

        "DELETE /api/cases/{id} returns 204 and makes the case invisible in subsequent listings" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-delete"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "to-delete" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(delete("/api/cases/$caseId"))
                .andExpect(status().isNoContent)

            // The deleted case should no longer appear in listings
            mockMvc.perform(get("/api/cases/by-parentId/${ns.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.id == '$caseId')]", org.hamcrest.Matchers.hasSize<Any>(0)))
        }

        "DELETE /api/cases/{id} returns 404 on a second attempt (already soft-deleted)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-delete-twice"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(delete("/api/cases/$caseId")).andExpect(status().isNoContent)
            mockMvc.perform(delete("/api/cases/$caseId")).andExpect(status().isNotFound)
        }
    }
}

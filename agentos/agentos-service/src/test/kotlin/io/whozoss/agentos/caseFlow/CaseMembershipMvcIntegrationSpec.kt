package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [CaseMembershipController].
 *
 * Uses the `test` + `embedded-neo4j` profiles. The permissive
 * [io.whozoss.agentos.permissions.InMemoryPermissionServiceImpl] means every
 * `hasPermission` call returns true, so 403 paths are not exercised here.
 *
 * Focuses on:
 * - GET /api/cases/{id}/members returns 200 with JSON array
 * - PATCH /api/cases/{id}/members with valid body returns 200
 * - PATCH with invalid body (duplicate userId) returns 400
 * - 404 when case does not exist
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CaseMembershipMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var userService: UserService

    init {

        // -------------------------------------------------------------------------
        // GET /api/cases/{id}/members
        // -------------------------------------------------------------------------

        "GET /api/cases/{id}/members returns 200 with JSON array" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "membership-get-ns"),
            )
            val caseId = createCaseViaHttp(ns.id.toString())

            mockMvc.perform(get("/api/cases/$caseId/members"))
                .andExpect(status().isOk)
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray)
        }

        "GET /api/cases/{id}/members returns 404 when case does not exist" {
            mockMvc.perform(get("/api/cases/${UUID.randomUUID()}/members"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // PATCH /api/cases/{id}/members
        // -------------------------------------------------------------------------

        "PATCH /api/cases/{id}/members with valid body returns 200" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "membership-patch-ns"),
            )
            val caseId = createCaseViaHttp(ns.id.toString())
            val target = userService.resolveOrCreateByExternalId("patch-target-${UUID.randomUUID()}@example.com")

            mockMvc.perform(
                patch("/api/cases/$caseId/members")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""[{"userId":"${target.id}","role":"MEMBER"}]"""),
            ).andExpect(status().isOk)
        }

        "PATCH /api/cases/{id}/members with duplicate userId returns 400" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "membership-dup-ns"),
            )
            val caseId = createCaseViaHttp(ns.id.toString())
            val id = UUID.randomUUID()

            mockMvc.perform(
                patch("/api/cases/$caseId/members")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""[{"userId":"$id","role":"MEMBER"},{"userId":"$id","role":"ADMIN"}]"""),
            ).andExpect(status().isBadRequest)
        }

        "PATCH /api/cases/{id}/members returns 404 when case does not exist" {
            val target = userService.resolveOrCreateByExternalId("patch-404-${UUID.randomUUID()}@example.com")

            mockMvc.perform(
                patch("/api/cases/${UUID.randomUUID()}/members")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""[{"userId":"${target.id}","role":"MEMBER"}]"""),
            ).andExpect(status().isNotFound)
        }
    }

    private fun createCaseViaHttp(namespaceId: String): UUID {
        val response = mockMvc.perform(
            post("/api/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"namespaceId":"$namespaceId","title":"membership test"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val id = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(response)!!.groupValues[1]
        return UUID.fromString(id)
    }
}

package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.api.case.CaseRole
import io.whozoss.agentos.sdk.api.case.CaseShareEntry
import io.whozoss.agentos.sdk.api.case.CaseShareRequest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for the case-sharing endpoints in [CaseController].
 *
 * Cases are created via `POST /api/cases` (not directly through the service) so that
 * the test user receives the auto-granted ADMIN edge on each case. Without that edge,
 * `@PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")` returns false and the
 * `@HideOnAccessDenied` annotation converts the 403 to a 404 — making the test
 * indistinguishable from a missing-case scenario.
 *
 * The first user resolved in this process is auto-promoted to super-admin (first-user
 * bootstrap), so `hasPermission` always returns true for namespace-level checks and
 * the auto-ADMIN grant on each new case succeeds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CaseSharingMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var objectMapper: ObjectMapper

    init {

        // -------------------------------------------------------------------------
        // PUT /api/cases/{caseId}/share
        // -------------------------------------------------------------------------

        "PUT /api/cases/{caseId}/share returns 200 with applied userIds for MEMBER entry" {
            val ns = createNamespace("share-member-200")
            val caseId = createCaseViaHttp(ns.id.toString())
            val target = userService.resolveOrCreateByExternalId("share-target-${UUID.randomUUID()}@example.com")

            val request = CaseShareRequest(
                entries = listOf(CaseShareEntry(userId = target.id, role = CaseRole.MEMBER)),
            )

            mockMvc.perform(
                put("/api/cases/$caseId/share")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            )
                .andExpect(status().isOk)
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[\"${target.id}\"]" ))
        }

        "PUT /api/cases/{caseId}/share returns 200 with applied userIds for ADMIN entry" {
            val ns = createNamespace("share-admin-200")
            val caseId = createCaseViaHttp(ns.id.toString())
            val target = userService.resolveOrCreateByExternalId("share-admin-target-${UUID.randomUUID()}@example.com")

            val request = CaseShareRequest(
                entries = listOf(CaseShareEntry(userId = target.id, role = CaseRole.ADMIN)),
            )

            mockMvc.perform(
                put("/api/cases/$caseId/share")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            )
                .andExpect(status().isOk)
                .andExpect(content().json("[\"${target.id}\"]" ))
        }

        "PUT /api/cases/{caseId}/share returns 200 with empty array for revoke when user had no relation" {
            val ns = createNamespace("share-revoke-200")
            val caseId = createCaseViaHttp(ns.id.toString())
            val target = userService.resolveOrCreateByExternalId("share-revoke-target-${UUID.randomUUID()}@example.com")

            // Revoke with no prior relation — batchRevoke finds nothing to delete, returns empty.
            val request = CaseShareRequest(
                entries = listOf(CaseShareEntry(userId = target.id, role = null)),
            )

            mockMvc.perform(
                put("/api/cases/$caseId/share")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            )
                .andExpect(status().isOk)
                .andExpect(content().json("[]"))
        }

        "PUT /api/cases/{caseId}/share returns 200 with empty array for empty entries list" {
            val ns = createNamespace("share-empty-200")
            val caseId = createCaseViaHttp(ns.id.toString())

            val request = CaseShareRequest(entries = emptyList())

            mockMvc.perform(
                put("/api/cases/$caseId/share")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            )
                .andExpect(status().isOk)
                .andExpect(content().json("[]"))
        }

        "PUT /api/cases/{caseId}/share returns 404 when case does not exist" {
            val missingCaseId = UUID.randomUUID()
            val target = userService.resolveOrCreateByExternalId("share-404-target-${UUID.randomUUID()}@example.com")

            val request = CaseShareRequest(
                entries = listOf(CaseShareEntry(userId = target.id, role = CaseRole.MEMBER)),
            )

            mockMvc.perform(
                put("/api/cases/$missingCaseId/share")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isNotFound)
        }

        "PUT /api/cases/{caseId}/share silently skips non-existent userId and returns empty array" {
            val ns = createNamespace("share-skip-user")
            val caseId = createCaseViaHttp(ns.id.toString())
            val missingUserId = UUID.randomUUID()

            // Non-existent users are silently skipped by the Cypher MATCH on User.
            val request = CaseShareRequest(
                entries = listOf(CaseShareEntry(userId = missingUserId, role = CaseRole.MEMBER)),
            )

            mockMvc.perform(
                put("/api/cases/$caseId/share")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            )
                .andExpect(status().isOk)
                .andExpect(content().json("[]"))
        }

        // -------------------------------------------------------------------------
        // GET /api/cases/{caseId}/users
        // -------------------------------------------------------------------------

        "GET /api/cases/{caseId}/users returns 200 for a case the caller has READ on" {
            val ns = createNamespace("case-users-read")
            val caseId = createCaseViaHttp(ns.id.toString())

            // The test user holds ADMIN on this case (auto-granted on create), so
            // they appear in the user list. We just assert 200 and a non-empty JSON array.
            mockMvc.perform(get("/api/cases/$caseId/users"))
                .andExpect(status().isOk)
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray)
        }

        "GET /api/cases/{caseId}/users returns 404 when case does not exist" {
            val missingCaseId = UUID.randomUUID()

            mockMvc.perform(get("/api/cases/$missingCaseId/users"))
                .andExpect(status().isNotFound)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createNamespace(nameSuffix: String): Namespace =
        namespaceService.create(
            Namespace(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                name = "test-ns-$nameSuffix-${UUID.randomUUID()}",
            ),
        )

    /**
     * Creates a case via the REST endpoint so the test user receives the auto-granted
     * ADMIN edge. Returns the case id as a [UUID].
     */
    private fun createCaseViaHttp(namespaceId: String): UUID {
        val response = mockMvc.perform(
            post("/api/cases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "namespaceId": "$namespaceId", "title": "test case" }""")
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val id = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(response)!!.groupValues[1]
        return UUID.fromString(id)
    }
}

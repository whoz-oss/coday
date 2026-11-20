package io.whozoss.agentos.caseFlow

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * `PUT /api/cases/{id}/keep-alive` end to end (security filter, `@PreAuthorize`, [CaseServiceImpl], embedded
 * Neo4j): WRITE on the case is required, as for the reasoning effort, and the flag is only changed there.
 *
 * The permission decision is mocked ([PermissionService]), like in
 * [io.whozoss.agentos.security.declarative.MethodSecurityIntegrationSpec], so that a refusal can be exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CaseKeepAliveEndpointIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var caseService: CaseService

    @Autowired lateinit var namespaceService: NamespaceService

    @MockkBean(relaxed = true) lateinit var userService: UserService

    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    private val member =
        User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "keep-alive@example.com",
            email = "keep-alive@example.com",
            isAdmin = false,
        )

    private fun newCase(): Case {
        val namespace = namespaceService.create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "keep-alive-${UUID.randomUUID()}"))
        return caseService.create(Case(namespaceId = namespace.id, title = "keep alive"))
    }

    private fun canWrite(
        case: Case,
        granted: Boolean,
    ) {
        every { permissionService.hasPermission(member.id.toString(), EntityType.CASE, case.id.toString(), Action.WRITE) } returns granted
    }

    private fun setKeepAlive(
        case: Case,
        body: String,
    ) = mockMvc.perform(put("/api/cases/${case.id}/keep-alive").contentType(MediaType.APPLICATION_JSON).content(body))

    init {
        beforeEach { every { userService.getCurrentUser() } returns member }

        "with WRITE on the case, turns the keep-alive on and off, and a rename keeps it" {
            val case = newCase()
            canWrite(case, granted = true)

            setKeepAlive(case, """{ "keepAlive": true }""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(case.id.toString()))
                .andExpect(jsonPath("$.keepAlive").value(true))
            caseService.findById(case.id)!!.keepAlive shouldBe true

            mockMvc
                .perform(
                    put("/api/cases/${case.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "id": "${case.id}", "namespaceId": "${case.namespaceId}", "title": "renamed", "keepAlive": false }"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.title").value("renamed"))
                .andExpect(jsonPath("$.keepAlive").value(true))

            setKeepAlive(case, """{ "keepAlive": false }""")
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.keepAlive").value(false))
            caseService.findById(case.id)!!.keepAlive shouldBe false
        }

        "without WRITE on the case (READ only), the change is refused with a 403" {
            val case = newCase()
            canWrite(case, granted = false)
            every { permissionService.hasPermission(member.id.toString(), EntityType.CASE, case.id.toString(), Action.READ) } returns true

            setKeepAlive(case, """{ "keepAlive": true }""").andExpect(status().isForbidden)

            caseService.findById(case.id)!!.keepAlive shouldBe false
        }

        "an unknown case is a 404" {
            val missing = Case(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = UUID.randomUUID())
            canWrite(missing, granted = true)

            setKeepAlive(missing, """{ "keepAlive": true }""").andExpect(status().isNotFound)
        }
    }
}

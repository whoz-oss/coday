package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [UserController] — verifies that Bean Validation is triggered
 * by the Spring MVC dispatcher on create and update endpoints.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" and
 * "embedded-neo4j" profiles so that the dispatcher, message converters, validation,
 * and the real Neo4j-backed [UserService] are all active.
 *
 * **Auth contract**: [AgentOsAuthenticationFilter] resolves the caller via
 * [io.whozoss.agentos.security.LocalSecurityService], which reads the OS username.
 * PUT tests that need a 200 therefore pre-create the user with [osIdentity] so that
 * the auth filter resolves the same record. Being the first user in a freshly-cleared
 * DB, that user becomes super-admin, satisfying the `@PreAuthorize` on the endpoint.
 *
 * These tests complement [UserControllerSpec], which exercises controller logic
 * directly without a Spring context (and therefore cannot test @Valid activation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UserControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var driver: Driver

    /**
     * OS identity resolved by [io.whozoss.agentos.security.LocalSecurityService].
     * Must match what [AgentOsAuthenticationFilter] will resolve for every request
     * in this test context.
     */
    private val osIdentity: String =
        System.getProperty("user.name")
            ?: System.getenv("USER")
            ?: System.getenv("USERNAME")
            ?: "test-user"

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        // -------------------------------------------------------------------------
        // POST /api/users — create
        // -------------------------------------------------------------------------

        "POST /api/users with no email returns 201" {
            // email is optional — omitting it is valid (local mode: no email known)
            mockMvc.perform(
                post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{}"""),
            ).andExpect(status().isCreated)
        }

        "POST /api/users with invalid email format returns 400" {
            mockMvc.perform(
                post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "not-an-email" }"""),
            ).andExpect(status().isBadRequest)
        }

        "POST /api/users with valid email returns 201" {
            mockMvc.perform(
                post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "alice@example.com" }"""),
            ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // PUT /api/users/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/users/{id} with invalid email format returns 400" {
            val id = UUID.randomUUID()

            mockMvc.perform(
                put("/api/users/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "email": "bad" }"""),
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/users/{id} with null email returns 200" {
            val created = userService.resolveOrCreateByExternalId(osIdentity)

            // email omitted (null) — valid in local mode
            mockMvc.perform(
                put("/api/users/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}" }"""),
            ).andExpect(status().isOk)
        }

        "PUT /api/users/{id} with valid email returns 200" {
            val created = userService.resolveOrCreateByExternalId(osIdentity)

            mockMvc.perform(
                put("/api/users/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "email": "alice@example.com" }"""),
            ).andExpect(status().isOk)
        }
    }
}

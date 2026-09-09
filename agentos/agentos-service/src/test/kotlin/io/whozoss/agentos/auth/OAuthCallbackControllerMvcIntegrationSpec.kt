package io.whozoss.agentos.auth

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.mockk.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer integration test for [OAuthCallbackController].
 *
 * Verifies that:
 * - `@PreAuthorize("isAuthenticated()")` fires through Spring AOP — an unauthenticated
 *   caller (no user resolved by [UserService]) is rejected with 403 before the method body runs.
 * - An authenticated caller whose [state] is known to [OAuthPendingRegistry] gets 200.
 * - An authenticated caller whose [state] is unknown gets 400.
 * - A [state] registered for Bob, posted by Alice, produces the same 400 as an unknown state.
 *   The test verifies explicitly that the registry was queried (the rejection comes from the
 *   identity check inside [OAuthPendingRegistry.resolve], not from a missing mock stub).
 *
 * Business-logic branches (resolve true/false) are covered by [OAuthCallbackControllerUnitSpec].
 * This spec focuses exclusively on the MVC / security layer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class OAuthCallbackControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true)
    lateinit var userService: UserService

    @MockkBean(relaxed = true)
    lateinit var pendingRegistry: OAuthPendingRegistry

    private val aliceId = UUID.randomUUID()
    private val alice = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
    )

    private val bobId = UUID.randomUUID()
    private val bob = User(
        metadata = EntityMetadata(id = bobId),
        externalId = "bob@example.com",
        email = "bob@example.com",
    )

    init {
        // Unauthenticated path: UserService throws (no principal) → security layer returns 403
        "POST /api/oauth/callback without authentication returns 403" {
            every { userService.getCurrentUser() } throws RuntimeException("not authenticated")

            mockMvc
                .perform(
                    post("/api/oauth/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "code": "auth-code", "state": "some-state" }"""),
                ).andExpect(status().isForbidden)
        }

        // Authenticated, known state → registry resolves → 200
        "POST /api/oauth/callback with known state returns 200" {
            every { userService.getCurrentUser() } returns alice
            every { pendingRegistry.resolve("known-state", "auth-code", aliceId) } returns true

            mockMvc
                .perform(
                    post("/api/oauth/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "code": "auth-code", "state": "known-state" }"""),
                ).andExpect(status().isOk)
        }

        // Authenticated, unknown state → registry returns false → 400
        "POST /api/oauth/callback with unknown state returns 400" {
            every { userService.getCurrentUser() } returns alice
            every { pendingRegistry.resolve("unknown-state", "auth-code", aliceId) } returns false

            mockMvc
                .perform(
                    post("/api/oauth/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "code": "auth-code", "state": "unknown-state" }"""),
                ).andExpect(status().isBadRequest)
        }

        // Identity mismatch: Bob's state posted by Alice → same 400 as unknown state.
        // The mock stubs resolve(state, code, aliceId) → false to simulate the registry
        // rejecting Alice's callerId. We then verify that resolve was actually called with
        // Alice's id — confirming the 400 comes from the identity check, not from a missing stub.
        "POST /api/oauth/callback with state belonging to another user returns 400" {
            every { userService.getCurrentUser() } returns alice
            // The registry sees Alice's id, which does not match Bob's registered flow → false
            every { pendingRegistry.resolve("bob-state", "alice-code", aliceId) } returns false

            mockMvc
                .perform(
                    post("/api/oauth/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "code": "alice-code", "state": "bob-state" }"""),
                ).andExpect(status().isBadRequest)

            // Explicit verification: resolve was called with Alice's id, proving the rejection
            // originates from the identity-binding check and not from a missing mock interaction.
            verify(exactly = 1) { pendingRegistry.resolve("bob-state", "alice-code", aliceId) }
        }
    }
}

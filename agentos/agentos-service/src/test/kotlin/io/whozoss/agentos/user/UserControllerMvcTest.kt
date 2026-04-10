package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * MVC-layer test for [UserController] — verifies that Bean Validation is triggered
 * by the Spring MVC dispatcher on create and update endpoints.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" profile
 * so that the dispatcher, message converters, and validation are all active.
 * The "test" profile enables in-memory persistence so no external services are needed.
 *
 * These tests complement [UserControllerSpec], which exercises the controller logic
 * directly without a Spring context (and therefore cannot test @Valid activation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerMvcTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userService: UserService

    init {

        // -------------------------------------------------------------------------
        // POST /api/users — create
        // -------------------------------------------------------------------------

        "POST /api/users with no email returns 201" {
            // email is now optional — omitting it is valid (local mode: no email known)
            mockMvc.perform(
                post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{}""")
            ).andExpect(status().isCreated)
        }

        "POST /api/users with invalid email format returns 400" {
            mockMvc.perform(
                post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "not-an-email" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/users with valid email returns 201" {
            mockMvc.perform(
                post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "alice@example.com" }""")
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
                    .content("""{ "id": "$id", "email": "bad" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/users/{id} with null email returns 200" {
            val id = UUID.randomUUID()

            // Pre-create the user so the update finds it
            val created = userService.create(
                User(
                    metadata = EntityMetadata(id = id),
                    externalId = "local-username",
                )
            )

            // email omitted (null) — valid in local mode
            mockMvc.perform(
                put("/api/users/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}" }""")
            ).andExpect(status().isOk)
        }

        "PUT /api/users/{id} with valid email returns 200" {
            val id = UUID.randomUUID()

            // Pre-create the user so the update finds it
            val created = userService.create(
                User(
                    metadata = EntityMetadata(id = id),
                    externalId = "local-username",
                )
            )

            mockMvc.perform(
                put("/api/users/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "email": "alice@example.com" }""")
            ).andExpect(status().isOk)
        }
    }
}

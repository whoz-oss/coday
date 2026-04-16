package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [NamespaceController] — verifies that Bean Validation is
 * triggered by the Spring MVC dispatcher on create and update endpoints.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" profile
 * so that the dispatcher, message converters, and validation are all active.
 * The "test" profile enables in-memory persistence so no external services are needed.
 *
 * These tests complement [NamespaceControllerSpec], which exercises the controller
 * logic directly without a Spring context (and therefore cannot test @Valid activation).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NamespaceControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var namespaceService: NamespaceService

    init {

        // -------------------------------------------------------------------------
        // POST /api/namespaces — create
        // -------------------------------------------------------------------------

        "POST /api/namespaces with blank name returns 400" {
            mockMvc.perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/namespaces with valid name returns 201" {
            mockMvc.perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "engineering" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/namespaces with valid configPath returns 201" {
            mockMvc.perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "coday", "configPath": "/opt/coday" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/namespaces with path-traversal in configPath returns 201" {
            mockMvc.perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "coday", "configPath": "../../etc/passwd" }""")
            ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // PUT /api/namespaces/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/namespaces/{id} with blank name returns 400" {
            val id = UUID.randomUUID()

            mockMvc.perform(
                put("/api/namespaces/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "name": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/namespaces/{id} with valid payload returns 200" {
            val created = namespaceService.create(
                Namespace(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    name = "platform",
                )
            )

            mockMvc.perform(
                put("/api/namespaces/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "${created.id}", "name": "platform-updated", "configPath": "/opt/platform" }""")
            ).andExpect(status().isOk)
        }
    }
}

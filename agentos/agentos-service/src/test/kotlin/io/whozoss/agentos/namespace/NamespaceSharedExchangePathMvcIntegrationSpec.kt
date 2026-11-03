package io.whozoss.agentos.namespace

import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.security.SuperAdminCallerSpec
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.util.UUID

/**
 * `sharedExchangePath` end to end on the real security chain and embedded Neo4j, as the super-admin the
 * local identity is: it is stored and returned by the namespace endpoints, and the namespace-shared file
 * endpoints then serve that folder. Refusal of non-super-admins is covered at the unit level by
 * [NamespaceControllerSharedExchangePathSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class NamespaceSharedExchangePathMvcIntegrationSpec : SuperAdminCallerSpec() {
    @Autowired lateinit var mockMvc: MockMvc

    private fun createNamespace(extra: String): String =
        mockMvc
            .perform(
                post("/api/namespaces")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "shared-path-${UUID.randomUUID()}" $extra }"""),
            ).andExpect(status().isCreated)
            .andReturn()
            .response.contentAsString
            .let { Regex("\"id\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

    init {
        "a namespace created with an absolute sharedExchangePath returns it" {
            val folder = Files.createTempDirectory("agentos-mvc-shared-")

            val id = createNamespace(""", "sharedExchangePath": "${folder}" """)

            mockMvc.perform(get("/api/namespaces/$id")).andExpect(status().isOk).andExpect(jsonPath("$.sharedExchangePath").value(folder.toString()))
        }

        "a relative sharedExchangePath is refused on create" {
            mockMvc
                .perform(
                    post("/api/namespaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "name": "relative-${UUID.randomUUID()}", "sharedExchangePath": "relative/folder" }"""),
                ).andExpect(status().isBadRequest)
        }

        "an update that omits the field keeps it, and one that sets it replaces it" {
            val first = Files.createTempDirectory("agentos-mvc-shared-")
            val second = Files.createTempDirectory("agentos-mvc-shared-")
            val id = createNamespace(""", "sharedExchangePath": "${first}" """)

            mockMvc
                .perform(put("/api/namespaces/$id").contentType(MediaType.APPLICATION_JSON).content("""{ "id": "$id", "name": "renamed" }"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.sharedExchangePath").value(first.toString()))
            mockMvc
                .perform(
                    put("/api/namespaces/$id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "id": "$id", "name": "renamed", "sharedExchangePath": "${second}" }"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.sharedExchangePath").value(second.toString()))
        }

        "the namespace-shared file endpoints serve the configured folder" {
            val folder = Files.createTempDirectory("agentos-mvc-shared-")
            Files.writeString(folder.resolve("welcome.md"), "hello from the user's folder")
            val id = createNamespace(""", "sharedExchangePath": "${folder}" """)

            mockMvc
                .perform(get("/api/namespaces/$id/files/manifest"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.files[0].path").value("welcome.md"))
            mockMvc
                .perform(get("/api/namespaces/$id/files/content").param("path", "welcome.md"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content").value("hello from the user's folder"))
            mockMvc
                .perform(get("/api/namespaces/$id/files/content").param("path", "../outside.md"))
                .andExpect(status().isBadRequest)
        }
    }
}

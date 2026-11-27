package io.whozoss.agentos.plugin.filesystem

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC integration tests for `POST /api/admin/filesystem-caches/clear`: the `hasRole('SUPER_ADMIN')` guard,
 * end to end through the dispatcher.
 *
 * The number of directories cleared depends on the registries alive in the process, which this spec does not
 * control: it only checks that it is reported.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class FilesystemCacheControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true)
    lateinit var userService: UserService

    @Suppress("unused")
    @MockkBean(relaxed = true)
    lateinit var permissionService: PermissionService

    private val superAdmin =
        User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "root@example.com",
            email = "root@example.com",
            isAdmin = true,
        )

    private val regularUser =
        User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "alice@example.com",
            email = "alice@example.com",
            isAdmin = false,
        )

    init {
        "POST /api/admin/filesystem-caches/clear as super-admin returns 200 with the number of directories cleared" {
            every { userService.getCurrentUser() } returns superAdmin

            mockMvc
                .perform(post("/api/admin/filesystem-caches/clear"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.clearedDirectories").isNumber)
        }

        "POST /api/admin/filesystem-caches/clear as a non-admin returns 403" {
            every { userService.getCurrentUser() } returns regularUser

            mockMvc
                .perform(post("/api/admin/filesystem-caches/clear"))
                .andExpect(status().isForbidden)
        }
    }
}

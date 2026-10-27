package io.whozoss.agentos.tool

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * The `userId` of `GET /api/tools` and `POST /api/tools/invoke` becomes the identity the tools act
 * for, so a namespace admin may only name themselves; only a super-admin may name another user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class ToolInvokeControllerSecurityIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true) lateinit var userService: UserService

    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    @MockkBean lateinit var toolInvokeService: ToolInvokeService

    private val namespaceId = UUID.randomUUID()

    private fun user(isAdmin: Boolean): User {
        val id = UUID.randomUUID()
        return User(
            metadata = EntityMetadata(id = id),
            externalId = "tools-$id@example.com",
            email = "tools-$id@example.com",
            isAdmin = isAdmin,
        )
    }

    private val namespaceAdmin = user(isAdmin = false)
    private val superAdmin = user(isAdmin = true)
    private val otherUser = user(isAdmin = false)

    private fun actAs(caller: User) {
        every { userService.getCurrentUser() } returns caller
        every {
            permissionService.hasPermission(caller.id.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE)
        } returns true
    }

    private fun listTools(userId: UUID?): ResultActions =
        mockMvc.perform(
            get("/api/tools")
                .param("namespaceId", namespaceId.toString())
                .apply { userId?.let { param("userId", it.toString()) } },
        )

    /** `invoke` is a suspend handler: a granted call completes on the async dispatch. */
    private fun invokeTool(userId: UUID?): ResultActions {
        val userField = userId?.let { """, "userId": "$it"""" } ?: ""
        val actions =
            mockMvc.perform(
                post("/api/tools/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "toolName": "SOME__tool"$userField }"""),
            )
        val result = actions.andReturn()
        return if (result.request.isAsyncStarted) mockMvc.perform(asyncDispatch(result)) else actions
    }

    init {
        beforeEach {
            every { toolInvokeService.listTools(any(), any()) } returns emptyList()
            coEvery { toolInvokeService.invoke(any(), any(), any(), any()) } returns ToolExecutionResult.success("done")
        }

        "GET /api/tools refuses a namespace admin who names another user" {
            actAs(namespaceAdmin)

            listTools(userId = otherUser.id).andExpect(status().isForbidden)

            verify(exactly = 0) { toolInvokeService.listTools(any(), any()) }
        }

        "GET /api/tools lets a namespace admin name themselves or nobody" {
            actAs(namespaceAdmin)

            listTools(userId = namespaceAdmin.id).andExpect(status().isOk)
            listTools(userId = null).andExpect(status().isOk)
        }

        "GET /api/tools lets a super-admin name another user" {
            actAs(superAdmin)

            listTools(userId = otherUser.id).andExpect(status().isOk)
        }

        "POST /api/tools/invoke refuses a namespace admin who names another user" {
            actAs(namespaceAdmin)

            invokeTool(userId = otherUser.id).andExpect(status().isForbidden)

            coVerify(exactly = 0) { toolInvokeService.invoke(any(), any(), any(), any()) }
        }

        "POST /api/tools/invoke lets a namespace admin name themselves or nobody" {
            actAs(namespaceAdmin)

            invokeTool(userId = namespaceAdmin.id).andExpect(status().isOk)
            invokeTool(userId = null).andExpect(status().isOk)
        }

        "POST /api/tools/invoke lets a super-admin name another user" {
            actAs(superAdmin)

            invokeTool(userId = otherUser.id).andExpect(status().isOk)
        }
    }
}

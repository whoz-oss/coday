package io.whozoss.agentos.security.declarative

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * End-to-end integration tests for the Spring Security infrastructure.
 *
 * Verifies that:
 * - `@PreAuthorize` AOP is wired and fires
 * - `hasPermission(...)` SpEL delegates to [AgentOsPermissionEvaluator] → [PermissionService]
 * - `hasRole('SUPER_ADMIN')` resolves against the `User.isAdmin` flag
 * - `AccessDeniedException` is mapped: 403 by default, 404 when method is `@HideOnAccessDenied`
 *
 * Uses a test-fixture controller registered via [SecurityTestController] (also a `@RestController`,
 * but on a non-conflicting `/__test/` path), so it is only loaded inside this spec context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MethodSecurityIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true) lateinit var userService: UserService

    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    private val regularUser = User(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )

    private val superAdmin = User(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        externalId = "root@example.com",
        email = "root@example.com",
        isAdmin = true,
    )

    init {

        "endpoint without @PreAuthorize is reachable for any caller" {
            every { userService.getCurrentUser() } returns regularUser

            mockMvc.perform(get("/__test/permit-all"))
                .andExpect(status().isOk)
        }

        "@PreAuthorize hasPermission delegates to AgentOsPermissionEvaluator and grants" {
            val targetId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns regularUser
            every {
                permissionService.hasPermission(regularUser.id.toString(), "Test", targetId.toString(), Action.READ)
            } returns true

            mockMvc.perform(get("/__test/with-permission/$targetId"))
                .andExpect(status().isOk)
        }

        "@PreAuthorize hasPermission returns 403 when permission is denied" {
            val targetId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns regularUser
            every {
                permissionService.hasPermission(regularUser.id.toString(), "Test", targetId.toString(), Action.READ)
            } returns false

            mockMvc.perform(get("/__test/with-permission/$targetId"))
                .andExpect(status().isForbidden)
        }

        "@PreAuthorize hasRole('SUPER_ADMIN') grants access to super-admin" {
            every { userService.getCurrentUser() } returns superAdmin

            mockMvc.perform(get("/__test/admin-only"))
                .andExpect(status().isOk)
        }

        "@PreAuthorize hasRole('SUPER_ADMIN') refuses regular users" {
            every { userService.getCurrentUser() } returns regularUser

            mockMvc.perform(get("/__test/admin-only"))
                .andExpect(status().isForbidden)
        }

        "@HideOnAccessDenied turns 403 into 404 when permission is denied" {
            val targetId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns regularUser
            every {
                permissionService.hasPermission(regularUser.id.toString(), "Test", targetId.toString(), Action.READ)
            } returns false

            mockMvc.perform(get("/__test/hidden/$targetId"))
                .andExpect(status().isNotFound)
        }

        "@HideOnAccessDenied still returns 200 when permission is granted" {
            val targetId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns regularUser
            every {
                permissionService.hasPermission(regularUser.id.toString(), "Test", targetId.toString(), Action.READ)
            } returns true

            mockMvc.perform(get("/__test/hidden/$targetId"))
                .andExpect(status().isOk)
        }
    }
}

/**
 * Test-only controller fixture — not loaded by production component scan because the spec
 * runs in `test` profile and the path `/__test/` collides with no production endpoint.
 */
@RestController
@RequestMapping("/__test", produces = [MediaType.APPLICATION_JSON_VALUE])
class SecurityTestController {

    @GetMapping("/permit-all")
    fun permitAll(): Map<String, String> = mapOf("status" to "ok")

    @GetMapping("/with-permission/{id}")
    @PreAuthorize("hasPermission(#id, 'Test', 'READ')")
    fun withPermission(@PathVariable id: UUID): Map<String, String> = mapOf("id" to id.toString())

    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun adminOnly(): Map<String, String> = mapOf("status" to "admin")

    @GetMapping("/hidden/{id}")
    @PreAuthorize("hasPermission(#id, 'Test', 'READ')")
    @HideOnAccessDenied
    fun hidden(@PathVariable id: UUID): Map<String, String> = mapOf("id" to id.toString())
}

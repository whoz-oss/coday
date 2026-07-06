package io.whozoss.agentos.user

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC integration tests for the `isAdmin` self-rule introduced in the
 * "relax super-admin creation/update via API" hotfix.
 *
 * **What is tested at HTTP level:**
 *  - `POST /api/users` honors `isAdmin` from the body when the caller is a super-admin.
 *  - `PUT /api/users/{id}` honors `isAdmin` from the body when the caller is a super-admin
 *     AND the target is NOT the caller (promote/demote on another user).
 *  - `PUT /api/users/{id}` PRESERVES `existing.isAdmin` when the caller IS the target
 *     (self-rule — applies to both super-admins and non-admins).
 *
 * **Auth mechanism**: [UserService] is mocked, so `getCurrentUser()` controls the
 * identity that [io.whozoss.agentos.security.declarative.AgentOsAuthentication] carries
 * into Spring Security. `auth.name` ends up being `mockedUser.id.toString()`.
 *
 * Bean Validation and the basic happy-path stay covered by [UserControllerMvcIntegrationSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UserControllerSelfRuleIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true) lateinit var userService: UserService

    @Suppress("unused")
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    private val superAdminId = UUID.randomUUID()
    private val regularUserId = UUID.randomUUID()
    private val targetUserId = UUID.randomUUID()

    private val superAdmin = User(
        metadata = EntityMetadata(id = superAdminId),
        externalId = "root@example.com",
        email = "root@example.com",
        isAdmin = true,
    )

    private val regularUser = User(
        metadata = EntityMetadata(id = regularUserId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )

    init {

        // -----------------------------------------------------------------
        // POST /api/users — super-admin can create a super-admin (AC1)
        // -----------------------------------------------------------------

        "POST with isAdmin=true (caller=super-admin) persists isAdmin=true" {
            val captured = slot<User>()
            every { userService.getCurrentUser() } returns superAdmin
            every { userService.create(capture(captured)) } answers { firstArg() }

            mockMvc.perform(
                post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "promoted@example.com", "isAdmin": true }""")
            ).andExpect(status().isCreated)

            captured.captured.isAdmin shouldBe true
        }

        // -----------------------------------------------------------------
        // POST /api/users — default isAdmin=false stays false (AC2)
        // -----------------------------------------------------------------

        "POST without isAdmin (caller=super-admin) persists isAdmin=false" {
            val captured = slot<User>()
            every { userService.getCurrentUser() } returns superAdmin
            every { userService.create(capture(captured)) } answers { firstArg() }

            mockMvc.perform(
                post("/api/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "email": "regular@example.com" }""")
            ).andExpect(status().isCreated)

            captured.captured.isAdmin shouldBe false
        }

        // -----------------------------------------------------------------
        // PUT — super-admin promotes another user (AC4)
        // -----------------------------------------------------------------

        "PUT with isAdmin=true on OTHER user (caller=super-admin) persists isAdmin=true" {
            val target = User(
                metadata = EntityMetadata(id = targetUserId),
                externalId = "target@example.com",
                email = "target@example.com",
                isAdmin = false,
            )
            val captured = slot<User>()
            every { userService.getCurrentUser() } returns superAdmin
            every { userService.getById(targetUserId) } returns target
            every { userService.update(capture(captured)) } answers { firstArg() }

            mockMvc.perform(
                put("/api/users/$targetUserId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$targetUserId", "email": "target@example.com", "isAdmin": true }""")
            ).andExpect(status().isOk)

            captured.captured.isAdmin shouldBe true
        }

        // -----------------------------------------------------------------
        // PUT — super-admin demotes another user (AC5)
        // -----------------------------------------------------------------

        "PUT with isAdmin=false on OTHER user (caller=super-admin) persists isAdmin=false" {
            val target = User(
                metadata = EntityMetadata(id = targetUserId),
                externalId = "target@example.com",
                email = "target@example.com",
                isAdmin = true,
            )
            val captured = slot<User>()
            every { userService.getCurrentUser() } returns superAdmin
            every { userService.getById(targetUserId) } returns target
            every { userService.update(capture(captured)) } answers { firstArg() }

            mockMvc.perform(
                put("/api/users/$targetUserId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$targetUserId", "email": "target@example.com", "isAdmin": false }""")
            ).andExpect(status().isOk)

            captured.captured.isAdmin shouldBe false
        }

        // -----------------------------------------------------------------
        // PUT — self-rule blocks self-demote for a super-admin (AC6)
        // -----------------------------------------------------------------

        "PUT with isAdmin=false on SELF (caller=super-admin, existing isAdmin=true) preserves isAdmin=true" {
            val captured = slot<User>()
            every { userService.getCurrentUser() } returns superAdmin
            every { userService.getById(superAdminId) } returns superAdmin
            every { userService.update(capture(captured)) } answers { firstArg() }

            mockMvc.perform(
                put("/api/users/$superAdminId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$superAdminId", "email": "root@example.com", "isAdmin": false }""")
            ).andExpect(status().isOk)

            captured.captured.isAdmin shouldBe true   // PRESERVED
        }

        // -----------------------------------------------------------------
        // PUT — self-rule blocks self-promote for a regular user (AC7)
        // -----------------------------------------------------------------

        "PUT with isAdmin=true on SELF (caller=non-admin, existing isAdmin=false) preserves isAdmin=false" {
            val captured = slot<User>()
            every { userService.getCurrentUser() } returns regularUser
            every { userService.getById(regularUserId) } returns regularUser
            every { userService.update(capture(captured)) } answers { firstArg() }

            mockMvc.perform(
                put("/api/users/$regularUserId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$regularUserId", "email": "alice@example.com", "isAdmin": true }""")
            ).andExpect(status().isOk)

            captured.captured.isAdmin shouldBe false  // PRESERVED
        }

        // -----------------------------------------------------------------
        // PUT — replace semantic : a body omitting isAdmin resets it to false.
        // Documents the API contract and aligns with the rest of the API
        // (e.g. AiModelResource.priority defaulting to 0). Clients on PUT must
        // send the full state.
        // -----------------------------------------------------------------

        "PUT partial body (no isAdmin field) on OTHER super-admin resets isAdmin to false (replace-semantic)" {
            val target = User(
                metadata = EntityMetadata(id = targetUserId),
                externalId = "other-admin@example.com",
                email = "other-admin@example.com",
                isAdmin = true,
            )
            val captured = slot<User>()
            every { userService.getCurrentUser() } returns superAdmin
            every { userService.getById(targetUserId) } returns target
            every { userService.update(capture(captured)) } answers { firstArg() }

            mockMvc.perform(
                put("/api/users/$targetUserId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$targetUserId", "email": "other-admin@example.com", "firstname": "Renamed" }""")
            ).andExpect(status().isOk)

            // PUT is replace, not patch — omitted isAdmin field deserialises to default false.
            // The self-rule does NOT apply here because caller != target.
            captured.captured.isAdmin shouldBe false
        }

        // -----------------------------------------------------------------
        // PUT — non-admin on OTHER user → 403
        // (regression cover for adversarial finding F7a / F19)
        // -----------------------------------------------------------------

        "PUT on OTHER user when caller is non-admin returns 403" {
            every { userService.getCurrentUser() } returns regularUser

            mockMvc.perform(
                put("/api/users/$targetUserId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$targetUserId", "email": "target@example.com", "isAdmin": true }""")
            ).andExpect(status().isForbidden)

            // Pin: the 403 must come from @PreAuthorize, not from a relaxed-mock
            // findById returning null (which would yield 404). If the annotation
            // were ever removed, this assertion would fail.
            verify(exactly = 0) { userService.findById(targetUserId) }
        }
    }
}

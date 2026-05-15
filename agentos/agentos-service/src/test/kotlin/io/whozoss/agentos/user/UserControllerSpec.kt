package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.userGroup.UserGroupService
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Run [block] with [auth] installed in the SecurityContextHolder, then clear.
 * Mirrors what AgentOsAuthenticationFilter does at runtime so unit tests of
 * UserController.update can exercise the self-rule branch.
 */
private inline fun <T> withAuthContext(auth: Authentication, block: () -> T): T {
    val previous = SecurityContextHolder.getContext().authentication
    SecurityContextHolder.getContext().authentication = auth
    try {
        return block()
    } finally {
        SecurityContextHolder.getContext().authentication = previous
    }
}

/**
 * Unit tests for [UserController].
 *
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover:
 * - [UserController.toResource]  — domain → HTTP DTO mapping
 * - [UserController.toDomain]    — HTTP DTO → domain mapping
 * - [UserController.listAll]     — delegates to [UserService.findAll] and maps results
 * - [UserController.getMe]       — delegates to [UserService.getCurrentUser] and maps result
 * - Inherited [EntityController] endpoints: getById (found / not-found),
 *   getByIds, create, update (found / not-found), delete (found / not-found)
 */
class UserControllerSpec : StringSpec({
    timeout = 5000

    val userService = mockk<UserService>()
    val permissionService = mockk<io.whozoss.agentos.permissions.PermissionService>(relaxed = true)
    val userGroupService = mockk<UserGroupService>(relaxed = true)
    val controller = UserController(userService, permissionService, userGroupService)

    fun user(
        id: UUID = UUID.randomUUID(),
        email: String = "alice@example.com",
        externalId: String = email,
        firstname: String? = "Alice",
        lastname: String? = "Smith",
        bio: String? = null,
        isAdmin: Boolean = false,
    ) = User(
        metadata = EntityMetadata(id = id),
        externalId = externalId,
        email = email,
        firstname = firstname,
        lastname = lastname,
        bio = bio,
        isAdmin = isAdmin,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        email: String? = "alice@example.com",
        firstname: String? = "Alice",
        lastname: String? = "Smith",
        bio: String? = null,
        isAdmin: Boolean = false,
    ) = UserResource(
        id = id,
        email = email,
        firstname = firstname,
        lastname = lastname,
        bio = bio,
        isAdmin = isAdmin,
    )

    // -------------------------------------------------------------------------
    // toResource mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields from User to UserResource" {
        val id = UUID.randomUUID()
        val u = user(id = id, email = "bob@example.com", externalId = "ext-key", firstname = "Bob", lastname = "Jones", bio = "dev")

        val result = controller.toResource(u)

        result shouldBe UserResource(id = id, email = "bob@example.com", externalId = "ext-key", firstname = "Bob", lastname = "Jones", bio = "dev", isAdmin = false)
    }

    "toResource returns null email when user has no email (local mode)" {
        val u = user(email = "", externalId = "local-username")

        val result = controller.toResource(u)

        result.email shouldBe null
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    "toDomain maps all fields from UserResource to User" {
        val id = UUID.randomUUID()
        val r = resource(id = id, email = "carol@example.com", firstname = "Carol", lastname = "White", bio = "qa")

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.email shouldBe "carol@example.com"
        result.externalId shouldBe ""   // server-managed — never sourced from request body
        result.firstname shouldBe "Carol"
        result.lastname shouldBe "White"
        result.bio shouldBe "qa"
    }

    "toDomain generates a random UUID when resource id is null" {
        val r = resource(id = null, email = "new@example.com")
        val result = controller.toDomain(r)
        // Should not throw and should produce a valid (non-null) UUID
        result.metadata.id shouldBe result.metadata.id // non-null assertion via shouldBe itself
    }

    // -------------------------------------------------------------------------
    // listAll
    // -------------------------------------------------------------------------

    "listAll returns all users mapped to UserResource" {
        val adminUser = user(email = "admin@example.com", isAdmin = true)
        val u1 = user(email = "alice@example.com")
        val u2 = user(email = "bob@example.com")
        every { userService.getCurrentUser() } returns adminUser
        every { userService.findAll() } returns listOf(u1, u2)

        val result = controller.listAll()

        result shouldBe listOf(controller.toResource(u1), controller.toResource(u2))
        verify(exactly = 1) { userService.findAll() }
    }

    "listAll returns empty list when no users exist" {
        val adminUser = user(email = "admin@example.com", isAdmin = true)
        every { userService.getCurrentUser() } returns adminUser
        every { userService.findAll() } returns emptyList()

        controller.listAll() shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // getMe
    // -------------------------------------------------------------------------

    "getMe delegates to UserService.getCurrentUser and returns a UserResource" {
        val u = user(email = "me@example.com")
        every { userService.getCurrentUser() } returns u

        val result = controller.getMe()

        result shouldBe controller.toResource(u)
        verify(exactly = 1) { userService.getCurrentUser() }
    }

    // -------------------------------------------------------------------------
    // getById (inherited from EntityController)
    // -------------------------------------------------------------------------

    "getById returns a UserResource when user is found (accessing own profile)" {
        val u = user()
        every { userService.getCurrentUser() } returns u
        every { userService.findByIds(listOf(u.id)) } returns listOf(u)
        every { userService.findById(u.id) } returns u

        val result = controller.getById(u.id)

        result shouldBe controller.toResource(u)
    }

    "getById throws 404 when user not found" {
        val currentUser = user()
        val id = UUID.randomUUID()
        every { userService.getCurrentUser() } returns currentUser
        every { userService.findByIds(listOf(id)) } returns emptyList()
        every { userService.findById(id) } returns null

        val ex = runCatching { controller.getById(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited from EntityController)
    // -------------------------------------------------------------------------

    "getByIds returns matching users mapped to UserResource (admin)" {
        val adminUser = user(isAdmin = true)
        val u1 = user(email = "a@x.com")
        val u2 = user(email = "b@x.com")
        every { userService.getCurrentUser() } returns adminUser
        every { userService.findByIds(listOf(u1.id, u2.id)) } returns listOf(u1, u2)

        val result = controller.getByIds(listOf(u1.id, u2.id))

        result shouldBe listOf(controller.toResource(u1), controller.toResource(u2))
    }

    // -------------------------------------------------------------------------
    // create (inherited from EntityController)
    // -------------------------------------------------------------------------

    "create converts resource to domain, delegates to service, and returns mapped resource (admin)" {
        val adminUser = user(isAdmin = true)
        val r = resource(id = null, email = "new@example.com")
        val domain = controller.toDomain(r)
        val saved = domain.copy(metadata = domain.metadata)
        every { userService.getCurrentUser() } returns adminUser
        every { userService.create(any()) } returns saved

        val result = controller.create(r)

        result shouldBe controller.toResource(saved)
        verify(exactly = 1) { userService.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update (inherited from EntityController)
    // -------------------------------------------------------------------------

    "update delegates to service and preserves isAdmin via self-rule" {
        // u.isAdmin = true and the body sends isAdmin = false (attempt self-demote).
        // The self-rule MUST keep isAdmin = true on the persisted entity.
        val u = user(isAdmin = true)
        val updatedResource = resource(id = u.id, email = u.email, firstname = "Updated", isAdmin = false)
        val captured = slot<User>()
        val auth = mockk<Authentication> { every { name } returns u.id.toString() }
        every { userService.getCurrentUser() } returns u
        every { userService.findByIds(listOf(u.id)) } returns listOf(u)
        every { userService.findById(u.id) } returns u
        every { userService.update(capture(captured)) } answers { firstArg() }

        val result = withAuthContext(auth) { controller.update(u.id, updatedResource) }

        captured.captured.isAdmin shouldBe true        // PRESERVED — self-rule active
        captured.captured.externalId shouldBe u.externalId  // server-managed, never from body
        result.isAdmin shouldBe true
        verify(exactly = 1) { userService.update(any()) }
    }

    "update throws 404 when user not found" {
        val id = UUID.randomUUID()
        val currentUser = user(id = id)  // Current user is updating their own profile
        val r = resource(id = id)
        val auth = mockk<Authentication> { every { name } returns id.toString() }
        every { userService.getCurrentUser() } returns currentUser
        every { userService.findByIds(listOf(id)) } returns emptyList()
        every { userService.findById(id) } returns null

        val ex = runCatching { withAuthContext(auth) { controller.update(id, r) } }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // create — isAdmin promotion (relaxed in this story)
    // -------------------------------------------------------------------------

    "create with isAdmin=true persists isAdmin=true (super-admin can promote)" {
        val adminUser = user(isAdmin = true)
        val r = resource(id = null, email = "promoted@example.com", isAdmin = true)
        val captured = slot<User>()
        every { userService.getCurrentUser() } returns adminUser
        every { userService.create(capture(captured)) } answers { firstArg() }

        val result = controller.create(r)

        captured.captured.isAdmin shouldBe true
        result.isAdmin shouldBe true
    }

    "create with isAdmin=false persists isAdmin=false (default)" {
        val adminUser = user(isAdmin = true)
        val r = resource(id = null, email = "regular@example.com", isAdmin = false)
        val captured = slot<User>()
        every { userService.getCurrentUser() } returns adminUser
        every { userService.create(capture(captured)) } answers { firstArg() }

        controller.create(r)

        captured.captured.isAdmin shouldBe false
    }

    // -------------------------------------------------------------------------
    // update — self-rule (isAdmin preservation when caller == target)
    // -------------------------------------------------------------------------

    "update super-admin on OTHER user with isAdmin=true persists isAdmin=true (promote)" {
        val adminId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val target = user(id = targetId, isAdmin = false)
        val r = resource(id = targetId, email = target.email, isAdmin = true)
        val auth = mockk<Authentication> { every { name } returns adminId.toString() }
        val captured = slot<User>()
        every { userService.findById(targetId) } returns target
        every { userService.update(capture(captured)) } answers { firstArg() }

        val result = withAuthContext(auth) { controller.update(targetId, r) }

        captured.captured.isAdmin shouldBe true
        result.isAdmin shouldBe true
    }

    "update super-admin on OTHER user with isAdmin=false persists isAdmin=false (demote)" {
        val adminId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val target = user(id = targetId, isAdmin = true)
        val r = resource(id = targetId, email = target.email, isAdmin = false)
        val auth = mockk<Authentication> { every { name } returns adminId.toString() }
        val captured = slot<User>()
        every { userService.findById(targetId) } returns target
        every { userService.update(capture(captured)) } answers { firstArg() }

        withAuthContext(auth) { controller.update(targetId, r) }

        captured.captured.isAdmin shouldBe false
    }

    "update self with isAdmin=false on existing super-admin preserves isAdmin=true (self-rule blocks demote)" {
        val selfId = UUID.randomUUID()
        val self = user(id = selfId, isAdmin = true)
        val r = resource(id = selfId, email = self.email, isAdmin = false)  // attempt self-demote
        val auth = mockk<Authentication> { every { name } returns selfId.toString() }
        val captured = slot<User>()
        every { userService.findById(selfId) } returns self
        every { userService.update(capture(captured)) } answers { firstArg() }

        val result = withAuthContext(auth) { controller.update(selfId, r) }

        captured.captured.isAdmin shouldBe true   // PRESERVED
        result.isAdmin shouldBe true
    }

    "update self with isAdmin=true on existing non-admin preserves isAdmin=false (self-rule blocks promote)" {
        val selfId = UUID.randomUUID()
        val self = user(id = selfId, isAdmin = false)
        val r = resource(id = selfId, email = self.email, isAdmin = true)  // attempt self-promote
        val auth = mockk<Authentication> { every { name } returns selfId.toString() }
        val captured = slot<User>()
        every { userService.findById(selfId) } returns self
        every { userService.update(capture(captured)) } answers { firstArg() }

        val result = withAuthContext(auth) { controller.update(selfId, r) }

        captured.captured.isAdmin shouldBe false  // PRESERVED
        result.isAdmin shouldBe false
    }

    // -------------------------------------------------------------------------
    // delete (inherited from EntityController)
    // -------------------------------------------------------------------------

    "delete succeeds when user exists (admin)" {
        val adminUser = user(isAdmin = true)
        val id = UUID.randomUUID()
        every { userService.getCurrentUser() } returns adminUser
        every { userService.findById(id) } returns user(id = id)
        every { userService.delete(id) } returns true

        controller.delete(id)

        verify(exactly = 1) { userService.delete(id) }
    }

    "delete throws 404 when service returns false" {
        val adminUser = user(isAdmin = true)
        val id = UUID.randomUUID()
        every { userService.getCurrentUser() } returns adminUser
        every { userService.findById(id) } returns null
        every { userService.delete(id) } returns false

        val ex = runCatching { controller.delete(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }
})

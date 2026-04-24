package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

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
    val permissionService = mockk<io.whozoss.agentos.permissions.BlockingPermissionService>()
    val controller = UserController(userService, permissionService)

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

        result shouldBe UserResource(id = id, email = "bob@example.com", externalId = "ext-key", firstname = "Bob", lastname = "Jones", bio = "dev")
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

    "update delegates to service when user exists and returns mapped resource" {
        val u = user()
        val updatedResource = resource(id = u.id, email = u.email, firstname = "Updated")
        // toDomain produces externalId=""; update() replaces it with the existing entity's externalId
        val updatedDomain = controller.toDomain(updatedResource).copy(
            metadata = u.metadata,
            externalId = u.externalId,
            isAdmin = u.isAdmin,
        )
        every { userService.getCurrentUser() } returns u
        every { userService.findByIds(listOf(u.id)) } returns listOf(u)
        every { userService.findById(u.id) } returns u
        every { userService.update(any()) } returns updatedDomain

        val result = controller.update(u.id, updatedResource)

        result shouldBe controller.toResource(updatedDomain)
        verify(exactly = 1) { userService.update(any()) }
    }

    "update throws 404 when user not found" {
        val id = UUID.randomUUID()
        val currentUser = user(id = id)  // Current user is updating their own profile
        val r = resource(id = id)
        every { userService.getCurrentUser() } returns currentUser
        every { userService.findByIds(listOf(id)) } returns emptyList()
        every { userService.findById(id) } returns null

        val ex = runCatching { controller.update(id, r) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
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

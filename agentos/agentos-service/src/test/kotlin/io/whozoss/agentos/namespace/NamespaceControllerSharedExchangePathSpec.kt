package io.whozoss.agentos.namespace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.namespace.NamespaceDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import java.util.UUID

/**
 * Who may set `sharedExchangePath` on a namespace update: only a super-admin, whatever the caller's rights
 * on the namespace (the `@PreAuthorize` of `update` lets a namespace admin in, this rule is the second gate).
 * Authorization on the HTTP path is covered by [NamespaceControllerMvcIntegrationSpec].
 */
class NamespaceControllerSharedExchangePathSpec : StringSpec({
    val namespaceService = mockk<NamespaceService>()
    val userService = mockk<UserService>()
    val controller = NamespaceController(namespaceService, userService, mockk<PermissionService>(relaxed = true))

    fun user(isAdmin: Boolean) = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "u", email = "u@example.com", isAdmin = isAdmin)

    fun stored(path: String?) = Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "ns", sharedExchangePath = path)

    fun dto(
        existing: Namespace,
        path: String?,
    ) = NamespaceDto(id = existing.id, name = "ns", sharedExchangePath = path)

    fun update(
        existing: Namespace,
        caller: User,
        path: String?,
    ): Namespace {
        every { namespaceService.findById(existing.id) } returns existing
        every { userService.getCurrentUser() } returns caller
        val saved = slot<Namespace>()
        every { namespaceService.update(capture(saved)) } answers { saved.captured }
        controller.update(existing.id, dto(existing, path))
        return saved.captured
    }

    "a super-admin sets the path" {
        update(stored(null), user(isAdmin = true), "/srv/shared").sharedExchangePath shouldBe "/srv/shared"
    }

    "a super-admin changes and clears the path" {
        update(stored("/srv/old"), user(isAdmin = true), "/srv/new").sharedExchangePath shouldBe "/srv/new"
        update(stored("/srv/old"), user(isAdmin = true), "  ").sharedExchangePath shouldBe null
    }

    "a namespace admin who is not a super-admin cannot set or change it" {
        val admin = user(isAdmin = false)

        shouldThrow<AccessDeniedException> { update(stored(null), admin, "/srv/shared") }
        shouldThrow<AccessDeniedException> { update(stored("/srv/old"), admin, "/srv/new") }
        shouldThrow<AccessDeniedException> { update(stored("/srv/old"), admin, "") }
        verify(exactly = 0) { namespaceService.update(match { it.sharedExchangePath == "/srv/shared" }) }
    }

    "sending the stored value back unchanged is fine for anyone, as a form round-trips the whole resource" {
        update(stored("/srv/shared"), user(isAdmin = false), "/srv/shared").sharedExchangePath shouldBe "/srv/shared"
        update(stored("/srv/shared"), user(isAdmin = false), " /srv/shared ").sharedExchangePath shouldBe "/srv/shared"
    }

    "an omitted value keeps the stored one, for a client that does not know the field" {
        update(stored("/srv/shared"), user(isAdmin = false), null).sharedExchangePath shouldBe "/srv/shared"
        update(stored(null), user(isAdmin = false), null).sharedExchangePath shouldBe null
    }

    "a relative path is refused" {
        shouldThrow<BadRequestException> { update(stored(null), user(isAdmin = true), "relative/folder") }
    }

    "the field is exposed in the DTO" {
        stored("/srv/shared").toDto().sharedExchangePath shouldBe "/srv/shared"
    }
})

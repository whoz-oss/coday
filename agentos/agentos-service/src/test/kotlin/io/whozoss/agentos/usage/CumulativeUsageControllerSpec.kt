package io.whozoss.agentos.usage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.usageRecord.CumulativeUsageDto
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class CumulativeUsageControllerSpec :
    StringSpec({
        val usage = CumulativeUsageDto(3.0, 0, 10, 6, 4, 0, 0)

        class Fixture(
            val user: User,
        ) {
            val costs = mockk<RunCostService>()
            val permissions = mockk<PermissionService>()
            val users = mockk<UserService>()
            val controller = CumulativeUsageController(costs, permissions, users)

            init {
                every { users.getCurrentUser() } returns user
                every { costs.cumulativeUsage(any()) } returns usage
            }
        }

        fun reader() = User(externalId = "reader", email = "reader@example.com", isAdmin = false)

        "every requested case must be readable before any ancestry or usage is inspected" {
            val f = Fixture(reader())
            every { f.permissions.hasPermission(any(), any(), any(), any()) } returns false
            shouldThrow<AccessDeniedException> { f.controller.cumulativeUsage(listOf(UUID.randomUUID())) }
            verify(exactly = 0) { f.costs.cumulativeUsage(any()) }
        }

        "a reader of every requested case receives the union" {
            val f = Fixture(reader())
            val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
            every { f.permissions.hasPermission(f.user.id.toString(), EntityType.CASE, any(), Action.READ) } returns true
            f.controller.cumulativeUsage(ids) shouldBe usage
            verify(exactly = 1) { f.costs.cumulativeUsage(ids) }
        }

        "one unreadable case among readable ones denies the whole batch and reveals nothing" {
            val f = Fixture(reader())
            val readable = UUID.randomUUID()
            val denied = UUID.randomUUID()
            every { f.permissions.hasPermission(any(), EntityType.CASE, readable.toString(), Action.READ) } returns true
            every { f.permissions.hasPermission(any(), EntityType.CASE, denied.toString(), Action.READ) } returns false
            shouldThrow<AccessDeniedException> { f.controller.cumulativeUsage(listOf(readable, denied)) }
            verify(exactly = 0) { f.costs.cumulativeUsage(any()) }
        }

        "an admin is served without any per-case permission lookup" {
            val f = Fixture(User(externalId = "admin", email = "admin@example.com", isAdmin = true))
            val ids = listOf(UUID.randomUUID())
            f.controller.cumulativeUsage(ids) shouldBe usage
            verify(exactly = 0) { f.permissions.hasPermission(any(), any(), any(), any()) }
            verify(exactly = 1) { f.costs.cumulativeUsage(ids) }
        }

        "the batch size is bounded before any permission lookup" {
            val f = Fixture(reader())
            listOf(emptyList(), List(201) { UUID.randomUUID() }).forEach { ids ->
                shouldThrow<ResponseStatusException> { f.controller.cumulativeUsage(ids) }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }
            verify(exactly = 0) { f.permissions.hasPermission(any(), any(), any(), any()) }
            verify(exactly = 0) { f.costs.cumulativeUsage(any()) }
        }
    })

package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import java.nio.file.Path
import java.util.UUID

/** Exercise the ordinary Exchange consumer against a provider with no Git dependency. */
class ExchangeControllerBoundarySpec : StringSpec({
    class Fixture {
        val case = Case(namespaceId = UUID.randomUUID())
        val user = User(externalId = "exchange-user", email = "exchange@example.com")
        val storage = mockk<ExchangeStorageService>(relaxed = true)
        val permissions = mockk<PermissionService> {
            every { hasPermission(user.id.toString(), EntityType.CASE, case.id.toString(), any()) } returns true
        }
        var root = ResolvedExchangeRoot(Path.of("/provider/documents"), case.id)
        var onMutation: () -> Unit = {}
        val resolver = object : ExchangeRootResolver {
            override fun resolve(case: Case) = root
            override fun resolve(caseId: UUID) = root
            override fun resolveNamespaceRoot(namespaceId: UUID) = Path.of("/provider/namespace")
            override fun <T> withCaseMutation(caseId: UUID, action: (ResolvedExchangeRoot) -> T): T {
                onMutation()
                return action(root)
            }
        }
        val cases = mockk<CaseService> { every { findById(case.id) } returns case }
        val controller = ExchangeController(
            storage,
            cases,
            ExchangeCapabilityService(permissions),
            mockk<UserService> { every { getCurrentUser() } returns user },
            resolver,
        )
    }

    "ordinary reads and mutations use the directory supplied by the Exchange provider" {
        val f = Fixture()

        f.controller.getCaseFilesManifest(f.case.id)
        f.controller.deleteCaseFile(f.case.id, "report.txt")

        verify { f.storage.listManifest(f.root.path, ExchangeScope.CASE) }
        verify { f.storage.delete(f.root.path, "report.txt") }
    }

    "sharing files never grants a child caller the owner's permissions" {
        val f = Fixture()
        val ownerId = UUID.randomUUID()
        f.root = f.root.copy(ownerCaseId = ownerId)
        every { f.permissions.hasPermission(f.user.id.toString(), EntityType.CASE, ownerId.toString(), any()) } returns false

        shouldThrow<AccessDeniedException> { f.controller.getCaseFilesManifest(f.case.id) }
        shouldThrow<AccessDeniedException> { f.controller.deleteCaseFile(f.case.id, "report.txt") }

        verify(exactly = 0) { f.storage.listManifest(any(), any()) }
        verify(exactly = 0) { f.storage.delete(any(), any()) }
    }

    "a mutation rechecks WRITE permission within the provider's critical section" {
        val f = Fixture()
        f.onMutation = {
            every { f.permissions.hasPermission(f.user.id.toString(), EntityType.CASE, f.case.id.toString(), Action.WRITE) } returns false
        }

        shouldThrow<AccessDeniedException> { f.controller.deleteCaseFile(f.case.id, "report.txt") }

        verify(exactly = 0) { f.storage.delete(any(), any()) }
    }

    "a case deleted before the provider admits a mutation remains inaccessible" {
        val f = Fixture()
        f.onMutation = { every { f.cases.findById(f.case.id) } returns null }

        shouldThrow<ResourceNotFoundException> { f.controller.deleteCaseFile(f.case.id, "report.txt") }

        verify(exactly = 0) { f.storage.delete(any(), any()) }
    }

    "an environment that becomes unavailable cannot write to a fallback directory" {
        val f = Fixture()
        f.onMutation = { f.root = f.root.copy(unavailableReason = "Environment is being removed") }

        shouldThrow<ExchangeUnavailableException> { f.controller.deleteCaseFile(f.case.id, "report.txt") }

        verify(exactly = 0) { f.storage.delete(any(), any()) }
        f.root.path shouldBe Path.of("/provider/documents")
    }
})

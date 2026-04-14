package io.whozoss.agentos.caseFlow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.auth.AccessDeniedException
import io.whozoss.agentos.auth.AuthorizationService
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.auth.Operation
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

class CaseControllerSpec : StringSpec({
    val caseService = mockk<CaseService>()
    val userService = mockk<UserService>()
    val authorizationService = mockk<AuthorizationService>()
    val roleRepository = mockk<RoleRepository>()
    val controller = CaseController(caseService, userService, authorizationService, roleRepository)

    val currentUserId = UUID.randomUUID()
    val currentUser = User(metadata = EntityMetadata(id = currentUserId), externalId = "user@test.com")
    val nsId = UUID.randomUUID()
    val caseId = UUID.randomUUID()
    val testCase = Case(metadata = EntityMetadata(id = caseId), namespaceId = nsId, title = "test-case")

    beforeEach {
        clearMocks(caseService, userService, authorizationService, roleRepository)
        every { userService.getCurrentUser() } returns currentUser
    }

    // -------------------------------------------------------------------------
    // getById
    // -------------------------------------------------------------------------

    "getById checks READ access" {
        every { authorizationService.requireCaseAccess(any(), any(), any()) } just Runs
        every { caseService.findById(caseId) } returns testCase

        val result = controller.getById(caseId)

        result.title shouldBe "test-case"
        verify { authorizationService.requireCaseAccess(currentUserId.toString(), caseId.toString(), Operation.READ) }
    }

    "getById throws 403 when access denied" {
        every {
            authorizationService.requireCaseAccess(any(), any(), any())
        } throws AccessDeniedException("Access denied")

        shouldThrow<AccessDeniedException> { controller.getById(caseId) }
    }

    // -------------------------------------------------------------------------
    // create + auto-assign case OWNER
    // -------------------------------------------------------------------------

    "create requires MEMBER namespace access and auto-assigns case OWNER" {
        every { authorizationService.requireNamespaceAccess(any(), any(), any()) } just Runs
        every { caseService.create(any()) } returns testCase
        every { roleRepository.assignCaseRole(any(), any(), any(), any()) } just Runs

        val resource = CaseResource(namespaceId = nsId, title = "test-case")
        val result = controller.create(resource)

        result.title shouldBe "test-case"
        verify { authorizationService.requireNamespaceAccess(currentUserId.toString(), nsId.toString(), NamespaceRole.MEMBER) }
        verify { roleRepository.assignCaseRole(currentUserId.toString(), caseId.toString(), CaseRole.OWNER, currentUserId.toString()) }
    }

    // -------------------------------------------------------------------------
    // update / delete
    // -------------------------------------------------------------------------

    "update checks WRITE access" {
        every { authorizationService.requireCaseAccess(any(), any(), any()) } just Runs
        every { caseService.findById(caseId) } returns testCase
        every { caseService.update(any()) } returns testCase

        controller.update(caseId, CaseResource(id = caseId, namespaceId = nsId, title = "updated"))

        verify { authorizationService.requireCaseAccess(currentUserId.toString(), caseId.toString(), Operation.WRITE) }
    }

    "delete checks DELETE access" {
        every { authorizationService.requireCaseAccess(any(), any(), any()) } just Runs
        every { caseService.delete(caseId) } returns true

        controller.delete(caseId)

        verify { authorizationService.requireCaseAccess(currentUserId.toString(), caseId.toString(), Operation.DELETE) }
    }

    // -------------------------------------------------------------------------
    // listByParent — filtered
    // -------------------------------------------------------------------------

    "listByParent returns only accessible cases" {
        val case2Id = UUID.randomUUID()
        val case2 = Case(metadata = EntityMetadata(id = case2Id), namespaceId = nsId, title = "case-2")
        every { authorizationService.filterAccessibleCaseIds(currentUserId.toString(), nsId.toString()) } returns setOf(caseId.toString())
        every { caseService.findByParent(nsId) } returns listOf(testCase, case2)

        val result = controller.listByParent(nsId)

        result shouldHaveSize 1
        result[0].title shouldBe "test-case"
    }

    // -------------------------------------------------------------------------
    // addMessage — EXECUTE access
    // -------------------------------------------------------------------------

    "addMessage checks EXECUTE access" {
        every { authorizationService.requireCaseAccess(any(), any(), any()) } just Runs
        every { caseService.addMessage(any(), any(), any(), any()) } just Runs

        controller.addMessage(caseId, AddMessageRequest(content = "hello"))

        verify { authorizationService.requireCaseAccess(currentUserId.toString(), caseId.toString(), Operation.EXECUTE) }
    }

    // -------------------------------------------------------------------------
    // interrupt / kill — MANAGE access
    // -------------------------------------------------------------------------

    "interruptCase checks MANAGE access" {
        every { authorizationService.requireCaseAccess(any(), any(), any()) } just Runs
        every { caseService.interruptCase(caseId) } just Runs

        controller.interruptCase(caseId)

        verify { authorizationService.requireCaseAccess(currentUserId.toString(), caseId.toString(), Operation.MANAGE) }
    }

    "killCase checks MANAGE access" {
        every { authorizationService.requireCaseAccess(any(), any(), any()) } just Runs
        every { caseService.killCase(caseId) } just Runs

        controller.killCase(caseId)

        verify { authorizationService.requireCaseAccess(currentUserId.toString(), caseId.toString(), Operation.MANAGE) }
    }
})

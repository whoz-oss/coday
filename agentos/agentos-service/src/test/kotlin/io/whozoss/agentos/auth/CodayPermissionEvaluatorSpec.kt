package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.auth.AccessDecision
import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.auth.Operation
import io.whozoss.agentos.sdk.auth.PermissionContext
import io.whozoss.agentos.sdk.auth.ToolCategory
import java.util.UUID

class CodayPermissionEvaluatorSpec : StringSpec() {

    private val evaluator = CodayPermissionEvaluator()

    private fun nsCtx(
        role: NamespaceRole? = null,
        isRoot: Boolean = false,
    ) = PermissionContext(
        userId = UUID.randomUUID(),
        isRoot = isRoot,
        namespaceId = UUID.randomUUID(),
        namespaceRole = role,
    )

    private fun caseCtx(
        role: CaseRole? = null,
        isRoot: Boolean = false,
    ) = PermissionContext(
        userId = UUID.randomUUID(),
        isRoot = isRoot,
        caseId = UUID.randomUUID(),
        caseRole = role,
    )

    init {
        // =====================================================================
        // evaluateNamespaceAccess — isRoot bypass
        // =====================================================================

        "isRoot bypasses namespace access for OWNER" {
            val decision = evaluator.evaluateNamespaceAccess(nsCtx(isRoot = true), NamespaceRole.OWNER)
            decision.isGranted shouldBe true
            (decision as AccessDecision.Granted).effectiveRole shouldBe "ROOT"
        }

        // =====================================================================
        // evaluateNamespaceAccess — full matrix NamespaceRole x minRole
        // =====================================================================

        "OWNER satisfies OWNER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.OWNER), NamespaceRole.OWNER).isGranted shouldBe true
        }

        "OWNER satisfies ADMIN" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.OWNER), NamespaceRole.ADMIN).isGranted shouldBe true
        }

        "OWNER satisfies MEMBER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.OWNER), NamespaceRole.MEMBER).isGranted shouldBe true
        }

        "OWNER satisfies VIEWER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.OWNER), NamespaceRole.VIEWER).isGranted shouldBe true
        }

        "ADMIN satisfies ADMIN" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.ADMIN), NamespaceRole.ADMIN).isGranted shouldBe true
        }

        "ADMIN satisfies MEMBER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.ADMIN), NamespaceRole.MEMBER).isGranted shouldBe true
        }

        "ADMIN satisfies VIEWER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.ADMIN), NamespaceRole.VIEWER).isGranted shouldBe true
        }

        "ADMIN does not satisfy OWNER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.ADMIN), NamespaceRole.OWNER).isGranted shouldBe false
        }

        "MEMBER satisfies MEMBER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.MEMBER), NamespaceRole.MEMBER).isGranted shouldBe true
        }

        "MEMBER satisfies VIEWER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.MEMBER), NamespaceRole.VIEWER).isGranted shouldBe true
        }

        "MEMBER does not satisfy ADMIN" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.MEMBER), NamespaceRole.ADMIN).isGranted shouldBe false
        }

        "MEMBER does not satisfy OWNER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.MEMBER), NamespaceRole.OWNER).isGranted shouldBe false
        }

        "VIEWER satisfies VIEWER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.VIEWER), NamespaceRole.VIEWER).isGranted shouldBe true
        }

        "VIEWER does not satisfy MEMBER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.VIEWER), NamespaceRole.MEMBER).isGranted shouldBe false
        }

        "VIEWER does not satisfy ADMIN" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.VIEWER), NamespaceRole.ADMIN).isGranted shouldBe false
        }

        "VIEWER does not satisfy OWNER" {
            evaluator.evaluateNamespaceAccess(nsCtx(NamespaceRole.VIEWER), NamespaceRole.OWNER).isGranted shouldBe false
        }

        // =====================================================================
        // evaluateNamespaceAccess — Abstain when context incomplete
        // =====================================================================

        "Abstain when namespace role is null" {
            val decision = evaluator.evaluateNamespaceAccess(nsCtx(role = null), NamespaceRole.VIEWER)
            decision shouldBe AccessDecision.Abstain
        }

        // =====================================================================
        // evaluateCaseAccess — isRoot bypass
        // =====================================================================

        "isRoot bypasses case access for DELETE" {
            val decision = evaluator.evaluateCaseAccess(caseCtx(isRoot = true), Operation.DELETE)
            decision.isGranted shouldBe true
            (decision as AccessDecision.Granted).effectiveRole shouldBe "ROOT"
        }

        // =====================================================================
        // evaluateCaseAccess — OWNER matrix (all operations allowed)
        // =====================================================================

        "OWNER can LIST" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OWNER), Operation.LIST).isGranted shouldBe true
        }

        "OWNER can READ" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OWNER), Operation.READ).isGranted shouldBe true
        }

        "OWNER can CREATE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OWNER), Operation.CREATE).isGranted shouldBe true
        }

        "OWNER can WRITE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OWNER), Operation.WRITE).isGranted shouldBe true
        }

        "OWNER can DELETE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OWNER), Operation.DELETE).isGranted shouldBe true
        }

        "OWNER can MANAGE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OWNER), Operation.MANAGE).isGranted shouldBe true
        }

        "OWNER can EXECUTE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OWNER), Operation.EXECUTE).isGranted shouldBe true
        }

        // =====================================================================
        // evaluateCaseAccess — PARTICIPANT matrix
        // =====================================================================

        "PARTICIPANT can LIST" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.PARTICIPANT), Operation.LIST).isGranted shouldBe true
        }

        "PARTICIPANT can READ" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.PARTICIPANT), Operation.READ).isGranted shouldBe true
        }

        "PARTICIPANT cannot CREATE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.PARTICIPANT), Operation.CREATE).isGranted shouldBe false
        }

        "PARTICIPANT cannot WRITE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.PARTICIPANT), Operation.WRITE).isGranted shouldBe false
        }

        "PARTICIPANT cannot DELETE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.PARTICIPANT), Operation.DELETE).isGranted shouldBe false
        }

        "PARTICIPANT cannot MANAGE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.PARTICIPANT), Operation.MANAGE).isGranted shouldBe false
        }

        "PARTICIPANT can EXECUTE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.PARTICIPANT), Operation.EXECUTE).isGranted shouldBe true
        }

        // =====================================================================
        // evaluateCaseAccess — OBSERVER matrix
        // =====================================================================

        "OBSERVER can LIST" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OBSERVER), Operation.LIST).isGranted shouldBe true
        }

        "OBSERVER can READ" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OBSERVER), Operation.READ).isGranted shouldBe true
        }

        "OBSERVER cannot CREATE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OBSERVER), Operation.CREATE).isGranted shouldBe false
        }

        "OBSERVER cannot WRITE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OBSERVER), Operation.WRITE).isGranted shouldBe false
        }

        "OBSERVER cannot DELETE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OBSERVER), Operation.DELETE).isGranted shouldBe false
        }

        "OBSERVER cannot MANAGE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OBSERVER), Operation.MANAGE).isGranted shouldBe false
        }

        "OBSERVER cannot EXECUTE" {
            evaluator.evaluateCaseAccess(caseCtx(CaseRole.OBSERVER), Operation.EXECUTE).isGranted shouldBe false
        }

        // =====================================================================
        // evaluateCaseAccess — Abstain when context incomplete
        // =====================================================================

        "Abstain when case role is null" {
            val decision = evaluator.evaluateCaseAccess(caseCtx(role = null), Operation.READ)
            decision shouldBe AccessDecision.Abstain
        }

        // =====================================================================
        // evaluateToolAccess — isRoot bypass
        // =====================================================================

        "isRoot bypasses tool access for ADMIN category" {
            val decision = evaluator.evaluateToolAccess(caseCtx(isRoot = true), "admin-tool", ToolCategory.ADMIN)
            decision.isGranted shouldBe true
        }

        // =====================================================================
        // evaluateToolAccess — OWNER matrix (all categories allowed)
        // =====================================================================

        "OWNER can use READ_ONLY tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.OWNER), "tool", ToolCategory.READ_ONLY).isGranted shouldBe true
        }

        "OWNER can use WRITE tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.OWNER), "tool", ToolCategory.WRITE).isGranted shouldBe true
        }

        "OWNER can use DESTRUCTIVE tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.OWNER), "tool", ToolCategory.DESTRUCTIVE).isGranted shouldBe true
        }

        "OWNER can use ADMIN tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.OWNER), "tool", ToolCategory.ADMIN).isGranted shouldBe true
        }

        // =====================================================================
        // evaluateToolAccess — PARTICIPANT matrix
        // =====================================================================

        "PARTICIPANT can use READ_ONLY tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.PARTICIPANT), "tool", ToolCategory.READ_ONLY).isGranted shouldBe true
        }

        "PARTICIPANT can use WRITE tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.PARTICIPANT), "tool", ToolCategory.WRITE).isGranted shouldBe true
        }

        "PARTICIPANT cannot use DESTRUCTIVE tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.PARTICIPANT), "tool", ToolCategory.DESTRUCTIVE).isGranted shouldBe false
        }

        "PARTICIPANT cannot use ADMIN tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.PARTICIPANT), "tool", ToolCategory.ADMIN).isGranted shouldBe false
        }

        // =====================================================================
        // evaluateToolAccess — OBSERVER matrix
        // =====================================================================

        "OBSERVER can use READ_ONLY tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.OBSERVER), "tool", ToolCategory.READ_ONLY).isGranted shouldBe true
        }

        "OBSERVER cannot use WRITE tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.OBSERVER), "tool", ToolCategory.WRITE).isGranted shouldBe false
        }

        "OBSERVER cannot use DESTRUCTIVE tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.OBSERVER), "tool", ToolCategory.DESTRUCTIVE).isGranted shouldBe false
        }

        "OBSERVER cannot use ADMIN tool" {
            evaluator.evaluateToolAccess(caseCtx(CaseRole.OBSERVER), "tool", ToolCategory.ADMIN).isGranted shouldBe false
        }

        // =====================================================================
        // evaluateToolAccess — Abstain when context incomplete
        // =====================================================================

        "Abstain tool access when case role is null" {
            val decision = evaluator.evaluateToolAccess(caseCtx(role = null), "tool", ToolCategory.READ_ONLY)
            decision shouldBe AccessDecision.Abstain
        }

        // =====================================================================
        // getAvailableToolCategories
        // =====================================================================

        "isRoot gets all tool categories" {
            val categories = evaluator.getAvailableToolCategories(caseCtx(isRoot = true))
            categories shouldBe ToolCategory.entries.toSet()
        }

        "OWNER gets all tool categories" {
            val categories = evaluator.getAvailableToolCategories(caseCtx(CaseRole.OWNER))
            categories shouldBe ToolCategory.entries.toSet()
        }

        "PARTICIPANT gets READ_ONLY and WRITE categories" {
            val categories = evaluator.getAvailableToolCategories(caseCtx(CaseRole.PARTICIPANT))
            categories shouldBe setOf(ToolCategory.READ_ONLY, ToolCategory.WRITE)
        }

        "OBSERVER gets only READ_ONLY category" {
            val categories = evaluator.getAvailableToolCategories(caseCtx(CaseRole.OBSERVER))
            categories shouldBe setOf(ToolCategory.READ_ONLY)
        }

        "null role gets empty tool categories" {
            val categories = evaluator.getAvailableToolCategories(caseCtx(role = null))
            categories shouldBe emptySet()
        }
    }
}

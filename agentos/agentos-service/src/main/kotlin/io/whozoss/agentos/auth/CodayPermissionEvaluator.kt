package io.whozoss.agentos.auth

import io.whozoss.agentos.sdk.auth.AccessDecision
import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.auth.Operation
import io.whozoss.agentos.sdk.auth.PermissionContext
import io.whozoss.agentos.sdk.auth.PermissionEvaluator
import io.whozoss.agentos.sdk.auth.ToolCategory
import org.pf4j.Extension

/**
 * Default RBAC permission evaluator for Coday.
 *
 * Pure function implementation — no I/O, no service injection.
 * All data comes from [PermissionContext].
 *
 * Annotated [@Extension] for PF4J discovery as the built-in evaluator.
 * A plugin-provided evaluator can override this by registering
 * a different [PermissionEvaluator] extension.
 *
 * ## CaseRole x Operation matrix (FR5d)
 *
 * | Operation | OWNER | PARTICIPANT | OBSERVER |
 * |-----------|-------|-------------|----------|
 * | LIST      |  yes  |     yes     |   yes    |
 * | READ      |  yes  |     yes     |   yes    |
 * | CREATE    |  yes  |      no     |    no    |
 * | WRITE     |  yes  |      no     |    no    |
 * | DELETE    |  yes  |      no     |    no    |
 * | MANAGE    |  yes  |      no     |    no    |
 * | EXECUTE   |  yes  |     yes     |    no    |
 *
 * ## CaseRole x ToolCategory matrix
 *
 * | ToolCategory | OWNER | PARTICIPANT | OBSERVER |
 * |--------------|-------|-------------|----------|
 * | READ_ONLY    |  yes  |     yes     |   yes    |
 * | WRITE        |  yes  |     yes     |    no    |
 * | DESTRUCTIVE  |  yes  |      no     |    no    |
 * | ADMIN        |  yes  |      no     |    no    |
 */
@Extension
class CodayPermissionEvaluator : PermissionEvaluator {

    override fun evaluateNamespaceAccess(ctx: PermissionContext, minRole: NamespaceRole): AccessDecision {
        when {
            ctx.isRoot -> return AccessDecision.Granted("ROOT")
        }

        val role = ctx.namespaceRole
            ?: return AccessDecision.Abstain

        return when {
            role.satisfies(minRole) -> AccessDecision.Granted(role.name)
            else -> AccessDecision.Denied(
                "Role ${role.name} does not satisfy required ${minRole.name}",
            )
        }
    }

    override fun evaluateCaseAccess(ctx: PermissionContext, operation: Operation): AccessDecision {
        when {
            ctx.isRoot -> return AccessDecision.Granted("ROOT")
        }

        val role = ctx.caseRole
            ?: return AccessDecision.Abstain

        val allowed = when (role) {
            CaseRole.OWNER -> true
            CaseRole.PARTICIPANT -> operation in PARTICIPANT_OPERATIONS
            CaseRole.OBSERVER -> operation in OBSERVER_OPERATIONS
        }

        return when {
            allowed -> AccessDecision.Granted(role.name)
            else -> AccessDecision.Denied(
                "CaseRole ${role.name} cannot perform ${operation.name}",
            )
        }
    }

    override fun evaluateToolAccess(
        ctx: PermissionContext,
        toolName: String,
        toolCategory: ToolCategory,
    ): AccessDecision {
        when {
            ctx.isRoot -> return AccessDecision.Granted("ROOT")
        }

        val role = ctx.caseRole
            ?: return AccessDecision.Abstain

        val allowedCategories = availableToolCategoriesForRole(role)

        return when {
            toolCategory in allowedCategories -> AccessDecision.Granted(role.name)
            else -> AccessDecision.Denied(
                "CaseRole ${role.name} cannot use tool '$toolName' (category: ${toolCategory.name})",
            )
        }
    }

    override fun getAvailableToolCategories(ctx: PermissionContext): Set<ToolCategory> {
        when {
            ctx.isRoot -> return ToolCategory.entries.toSet()
        }

        val role = ctx.caseRole
            ?: return emptySet()

        return availableToolCategoriesForRole(role)
    }

    private fun availableToolCategoriesForRole(role: CaseRole): Set<ToolCategory> = when (role) {
        CaseRole.OWNER -> ToolCategory.entries.toSet()
        CaseRole.PARTICIPANT -> setOf(ToolCategory.READ_ONLY, ToolCategory.WRITE)
        CaseRole.OBSERVER -> setOf(ToolCategory.READ_ONLY)
    }

    companion object {
        private val PARTICIPANT_OPERATIONS = setOf(
            Operation.LIST,
            Operation.READ,
            Operation.EXECUTE,
        )

        private val OBSERVER_OPERATIONS = setOf(
            Operation.LIST,
            Operation.READ,
        )
    }
}

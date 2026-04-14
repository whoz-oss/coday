package io.whozoss.agentos.sdk.auth

import org.pf4j.ExtensionPoint

interface PermissionEvaluator : ExtensionPoint {
    fun evaluateNamespaceAccess(ctx: PermissionContext, minRole: NamespaceRole): AccessDecision
    fun evaluateCaseAccess(ctx: PermissionContext, operation: Operation): AccessDecision
    fun evaluateToolAccess(ctx: PermissionContext, toolName: String, toolCategory: ToolCategory): AccessDecision
    fun getAvailableToolCategories(ctx: PermissionContext): Set<ToolCategory>
}

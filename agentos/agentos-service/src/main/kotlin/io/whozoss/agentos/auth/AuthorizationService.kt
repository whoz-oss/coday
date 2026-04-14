package io.whozoss.agentos.auth

import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.auth.Operation
import io.whozoss.agentos.sdk.auth.ToolCategory

/**
 * Central authorization service that orchestrates permission checks
 * via a pluggable [io.whozoss.agentos.sdk.auth.PermissionEvaluator].
 *
 * All access verification passes through this single point, extensible
 * by PF4J plugin for enterprise integrations.
 *
 * All IDs are [String] to align with Neo4j node ID convention used
 * throughout the codebase.
 */
interface AuthorizationService {

    // -------------------------------------------------------------------------
    // Namespace access
    // -------------------------------------------------------------------------

    /**
     * Require at least [minRole] in the given namespace.
     * @throws AccessDeniedException if the user does not satisfy the required role
     */
    fun requireNamespaceAccess(userId: String, namespaceId: String, minRole: NamespaceRole)

    /**
     * Return the set of namespace IDs accessible to the user.
     */
    fun filterAccessibleNamespaceIds(userId: String): Set<String>

    // -------------------------------------------------------------------------
    // Case access
    // -------------------------------------------------------------------------

    /**
     * Require permission for the given [operation] on a case.
     * @throws AccessDeniedException if the user is not allowed
     */
    fun requireCaseAccess(userId: String, caseId: String, operation: Operation)

    /**
     * Return the set of case IDs accessible to the user within a namespace.
     */
    fun filterAccessibleCaseIds(userId: String, namespaceId: String): Set<String>

    // -------------------------------------------------------------------------
    // Tool access
    // -------------------------------------------------------------------------

    /**
     * Check whether the user can execute a specific tool in a case.
     */
    fun canExecuteTool(userId: String, caseId: String, toolName: String, toolCategory: ToolCategory): Boolean

    /**
     * Return the set of tool names the user can execute in a case.
     */
    fun getAvailableTools(userId: String, caseId: String, allTools: Map<String, ToolCategory>): Set<String>

    // -------------------------------------------------------------------------
    // Root status
    // -------------------------------------------------------------------------

    /**
     * Check whether the user is a root (super-admin).
     */
    fun isRoot(userId: String): Boolean

    /**
     * Require root status.
     * @throws AccessDeniedException if the user is not root
     */
    fun requireRoot(userId: String)
}

package io.whozoss.agentos.auth

import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.auth.AccessDecision
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.sdk.auth.Operation
import io.whozoss.agentos.sdk.auth.PermissionContext
import io.whozoss.agentos.sdk.auth.PermissionEvaluator
import io.whozoss.agentos.sdk.auth.ToolCategory
import io.whozoss.agentos.security.SecurityConfigProperties
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Central authorization service implementation.
 *
 * Orchestrates [RoleRepository] + [PermissionEvaluator] to evaluate access.
 * Builds an immutable [PermissionContext] per request, delegates evaluation
 * to the evaluator, and interprets the [AccessDecision].
 *
 * The evaluator is resolved lazily: a PF4J plugin-provided evaluator takes
 * priority over the built-in [CodayPermissionEvaluator].
 *
 * ## Fail-closed behavior (NFR9, NFR10)
 *
 * Any unexpected exception during a permission check is caught:
 * - In permissive mode: access is granted (backward compatibility).
 * - In strict mode: access is denied (fail-closed).
 * The error is logged without causing service unavailability.
 *
 * ## No ThreadLocal (NFR12)
 *
 * All context is passed explicitly via method parameters.
 */
@Service
class AuthorizationServiceImpl(
    private val roleRepository: RoleRepository,
    private val pluginManager: PluginManager,
    private val securityConfigProperties: SecurityConfigProperties,
    private val namespaceService: NamespaceService,
    private val caseService: CaseService,
) : AuthorizationService {

    private val evaluator: PermissionEvaluator by lazy {
        val pluginEvaluators = pluginManager.getExtensions(PermissionEvaluator::class.java)
        val overrides = pluginEvaluators.filter { it !is CodayPermissionEvaluator }
        when {
            overrides.isNotEmpty() -> {
                logger.info { "Using plugin-provided PermissionEvaluator: ${overrides.first()::class.qualifiedName}" }
                overrides.first()
            }
            else -> {
                logger.info { "Using built-in CodayPermissionEvaluator" }
                CodayPermissionEvaluator()
            }
        }
    }

    private val permissive: Boolean get() = securityConfigProperties.permissive

    // -------------------------------------------------------------------------
    // Namespace access
    // -------------------------------------------------------------------------

    override fun requireNamespaceAccess(userId: String, namespaceId: String, minRole: NamespaceRole) {
        try {
            when {
                roleRepository.isRoot(userId) -> return
            }

            val role = roleRepository.findNamespaceRole(userId, namespaceId)
            when {
                role == null && permissive -> return
                role == null -> throw AccessDeniedException("No role assigned for user $userId in namespace $namespaceId")
            }

            val ctx = PermissionContext(
                userId = UUID.fromString(userId),
                isRoot = false,
                namespaceId = UUID.fromString(namespaceId),
                namespaceRole = role,
            )

            val decision = evaluator.evaluateNamespaceAccess(ctx, minRole)
            handleDecision(decision)
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: Exception) {
            handleUnexpectedError(e, "requireNamespaceAccess(userId=$userId, namespaceId=$namespaceId)")
        }
    }

    override fun filterAccessibleNamespaceIds(userId: String): Set<String> = try {
        when {
            roleRepository.isRoot(userId) -> namespaceService.findAll().map { it.id.toString() }.toSet()
            else -> roleRepository.findNamespaceIdsForUser(userId)
        }
    } catch (e: Exception) {
        logger.error(e) { "Failed to filter accessible namespace IDs for user $userId" }
        when {
            permissive -> namespaceService.findAll().map { it.id.toString() }.toSet()
            else -> emptySet()
        }
    }

    // -------------------------------------------------------------------------
    // Case access
    // -------------------------------------------------------------------------

    override fun requireCaseAccess(userId: String, caseId: String, operation: Operation) {
        try {
            when {
                roleRepository.isRoot(userId) -> return
            }

            val caseRole = roleRepository.findCaseRole(userId, caseId)
            when {
                caseRole == null && permissive -> return
                caseRole == null -> throw AccessDeniedException("No role assigned for user $userId in case $caseId")
            }

            val ctx = PermissionContext(
                userId = UUID.fromString(userId),
                isRoot = false,
                caseId = UUID.fromString(caseId),
                caseRole = caseRole,
            )

            val decision = evaluator.evaluateCaseAccess(ctx, operation)
            handleDecision(decision)
        } catch (e: AccessDeniedException) {
            throw e
        } catch (e: Exception) {
            handleUnexpectedError(e, "requireCaseAccess(userId=$userId, caseId=$caseId)")
        }
    }

    override fun filterAccessibleCaseIds(userId: String, namespaceId: String): Set<String> = try {
        when {
            roleRepository.isRoot(userId) -> caseService.findByParent(UUID.fromString(namespaceId))
                .map { it.id.toString() }.toSet()
            else -> roleRepository.findAccessibleCaseIdsForUser(userId, namespaceId)
        }
    } catch (e: Exception) {
        logger.error(e) { "Failed to filter accessible case IDs for user $userId in namespace $namespaceId" }
        when {
            permissive -> caseService.findByParent(UUID.fromString(namespaceId))
                .map { it.id.toString() }.toSet()
            else -> emptySet()
        }
    }

    // -------------------------------------------------------------------------
    // Tool access
    // -------------------------------------------------------------------------

    override fun canExecuteTool(
        userId: String,
        caseId: String,
        toolName: String,
        toolCategory: ToolCategory,
    ): Boolean = try {
        when {
            roleRepository.isRoot(userId) -> true
            else -> {
                val caseRole = roleRepository.findCaseRole(userId, caseId)
                when (caseRole) {
                    null -> permissive
                    else -> {
                        val ctx = PermissionContext(
                            userId = UUID.fromString(userId),
                            isRoot = false,
                            caseId = UUID.fromString(caseId),
                            caseRole = caseRole,
                        )
                        evaluator.evaluateToolAccess(ctx, toolName, toolCategory).isGranted
                    }
                }
            }
        }
    } catch (e: Exception) {
        logger.error(e) { "Failed to check tool access for user $userId, tool $toolName" }
        permissive
    }

    override fun getAvailableTools(
        userId: String,
        caseId: String,
        allTools: Map<String, ToolCategory>,
    ): Set<String> = try {
        when {
            roleRepository.isRoot(userId) -> allTools.keys
            else -> {
                val caseRole = roleRepository.findCaseRole(userId, caseId)
                when (caseRole) {
                    null -> when {
                        permissive -> allTools.keys
                        else -> emptySet()
                    }
                    else -> {
                        val ctx = PermissionContext(
                            userId = UUID.fromString(userId),
                            isRoot = false,
                            caseId = UUID.fromString(caseId),
                            caseRole = caseRole,
                        )
                        allTools.filter { (toolName, toolCategory) ->
                            evaluator.evaluateToolAccess(ctx, toolName, toolCategory).isGranted
                        }.keys
                    }
                }
            }
        }
    } catch (e: Exception) {
        logger.error(e) { "Failed to get available tools for user $userId in case $caseId" }
        when {
            permissive -> allTools.keys
            else -> emptySet()
        }
    }

    // -------------------------------------------------------------------------
    // Root status
    // -------------------------------------------------------------------------

    override fun isRoot(userId: String): Boolean = try {
        roleRepository.isRoot(userId)
    } catch (e: Exception) {
        logger.error(e) { "Failed to check root status for user $userId" }
        false
    }

    override fun requireRoot(userId: String) {
        when {
            !isRoot(userId) -> throw AccessDeniedException("User $userId is not root")
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun handleDecision(decision: AccessDecision) {
        when (decision) {
            is AccessDecision.Granted -> return
            is AccessDecision.Denied -> throw AccessDeniedException(decision.reason)
            is AccessDecision.Abstain -> when {
                permissive -> return
                else -> throw AccessDeniedException("Permission evaluation abstained and permissive mode is disabled")
            }
        }
    }

    private fun handleUnexpectedError(e: Exception, context: String) {
        logger.error(e) { "Permission check failed: $context" }
        when {
            permissive -> return
            else -> throw AccessDeniedException("Permission check failed: system error")
        }
    }

    companion object : KLogging()
}

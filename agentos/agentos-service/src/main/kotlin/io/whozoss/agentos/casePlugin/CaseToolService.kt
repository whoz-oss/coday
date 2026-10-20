package io.whozoss.agentos.casePlugin

import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service backing the read side of the `CASE` tool integration (`ReadCase`).
 *
 * Permission is handled at controller level on the REST path (`@PreAuthorize`), which a tool does not go
 * through. This service replays the same rules for the tool, so that the plugin configuration only wires
 * dependencies and holds no business logic. Like `@PreAuthorize`, which runs before the method body, the
 * permission is checked **before** the repository is read:
 * - anonymous calls (userId == null): fail-closed
 * - the caller must hold Case READ, via [PermissionService]. A case that does not exist is not readable
 *   either (the permission service is fail-closed), so it is refused here too
 * - the case must exist
 * - namespace isolation: it must belong to the caller's namespace
 *
 * Returning null signals NOT_FOUND or access-denied to the calling tool, which maps both to the same error,
 * so that it does not leak whether a case exists or is merely inaccessible. Checking the permission first also
 * means a caller without rights triggers no read of the case at all.
 *
 * One consequence: the permission is consulted for a case of another namespace too, since the namespace is
 * only known once the case is read. That is the order of the REST path as well.
 *
 * ## Why this service injects repositories, not [io.whozoss.agentos.caseFlow.CaseService]
 *
 * `CaseServiceImpl` depends on `AgentService`, which depends on `ToolRegistryService`, which collects every
 * `ToolPlugin` bean: injecting `CaseService` into the plugin configuration would close a cycle.
 *
 * ```
 * ToolRegistryService
 *   -> springToolPlugins: List<ToolPlugin>
 *     -> CasePluginConfiguration
 *       -> CaseService -> AgentService -> ToolRegistryService  <- cycle
 * ```
 *
 * [CaseRepository], [CaseEventService] and [PermissionService] have no upward dependency on
 * `ToolRegistryService`. Same pattern as [io.whozoss.agentos.userGroup.UserGroupToolService]. The launch side
 * does not need this: [CaseLaunchService] reaches `CaseService` through a `@Lazy` proxy.
 */
@Service
class CaseToolService(
    private val caseRepository: CaseRepository,
    private val caseEventService: CaseEventService,
    private val permissionService: PermissionService,
) {
    /**
     * Returns the ordered events of [caseId], or null when:
     * - [userId] is null (anonymous call)
     * - the caller lacks Case READ (which includes a case that does not exist)
     * - the case does not exist
     * - the case belongs to a namespace other than [namespaceId]
     *
     * The checks run in that order, and the repository is only read once the caller holds Case READ.
     */
    fun findCaseEvents(
        caseId: UUID,
        namespaceId: UUID,
        userId: UUID?,
    ): List<CaseEvent>? {
        val canRead =
            userId != null &&
                permissionService.hasPermission(
                    userId.toString(),
                    EntityType.CASE,
                    caseId.toString(),
                    Action.READ,
                )
        val inNamespace =
            canRead && caseRepository.findByIds(listOf(caseId)).firstOrNull()?.namespaceId == namespaceId
        return if (inNamespace) caseEventService.findByParent(caseId) else null
    }

    companion object : KLogging()
}

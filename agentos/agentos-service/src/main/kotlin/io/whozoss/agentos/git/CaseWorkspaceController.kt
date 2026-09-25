package io.whozoss.agentos.git

import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.user.UserService
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
class CaseWorkspaceController(
    private val roots: GitExchangeRootResolver,
    private val status: GitWorkspaceStatusService,
    private val lifecycle: GitWorkspaceLifecycleService,
    private val bindings: CaseResourceBindingService,
    private val users: UserService,
    private val permissions: PermissionService,
    private val capabilities: ExchangeCapabilityService,
) {
    @GetMapping("/api/cases/{caseId}/workspace")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    fun get(@PathVariable caseId: UUID): CaseWorkspaceView = status.view(authorizedRoot(caseId, Action.READ))

    @GetMapping("/api/namespaces/{namespaceId}/workspaces")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun list(@PathVariable namespaceId: UUID): List<CaseWorkspaceView> = bindings.findByParent(namespaceId)
        .filter { canRead(it.rootCaseId) }.map { get(it.rootCaseId) }

    @PostMapping("/api/cases/{caseId}/workspace/refresh")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun refresh(@PathVariable caseId: UUID): CaseWorkspaceView {
        val root = authorizedRoot(caseId, Action.WRITE)
        root.binding?.let { status.refresh(it, root.repositoryPath.toAbsolutePath().normalize()) }
        return get(caseId)
    }

    @PostMapping("/api/cases/{caseId}/workspace/retry")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun retry(@PathVariable caseId: UUID, @RequestBody(required = false) request: WorkspaceRetryRequest?): CaseWorkspaceView {
        authorizedRoot(caseId, Action.WRITE)
        if (request?.acknowledgeSetupReplay == true) lifecycle.acknowledgeSetup(caseId) else lifecycle.retry(caseId)
        return get(caseId)
    }

    private fun authorizedRoot(caseId: UUID, action: Action): GitExchangeRoot = roots.resolveGit(caseId).also {
        capabilities.requireCaseAccess(users.getCurrentUser().id.toString(), caseId, it.exchange, action)
    }

    private fun canRead(caseId: UUID): Boolean = permissions.hasPermission(
        users.getCurrentUser().id.toString(), EntityType.CASE, caseId.toString(), Action.READ,
    )
}

data class WorkspaceRetryRequest(val acknowledgeSetupReplay: Boolean = false)

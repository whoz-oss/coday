package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.CaseRepository
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
    private val roots: ExchangeRootResolver,
    private val status: GitWorkspaceStatusService,
    private val lifecycle: GitWorkspaceLifecycleService,
    private val bindings: CaseResourceBindingService,
    private val cases: CaseRepository,
    private val users: UserService,
    private val permissions: PermissionService,
    private val journal: io.whozoss.agentos.caseFlow.CaseCommandJournal,
    private val caseService: io.whozoss.agentos.caseFlow.CaseService,
) {
    @GetMapping("/api/cases/{caseId}/workspace")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    fun get(@PathVariable caseId: UUID): CaseWorkspaceView = status.view(roots.resolve(caseId)).let { view ->
        if (view.equipped) view.copy(recoveryRequired = journal.recoveryRequired(caseId) || cases.findByIds(listOf(caseId)).firstOrNull()?.status in setOf(io.whozoss.agentos.sdk.caseFlow.CaseStatus.KILLED, io.whozoss.agentos.sdk.caseFlow.CaseStatus.ERROR)) else view
    }

    @GetMapping("/api/namespaces/{namespaceId}/workspaces")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun list(@PathVariable namespaceId: UUID): List<CaseWorkspaceView> = bindings.findByParent(namespaceId)
        .filter { canRead(it.rootCaseId) }.map { get(it.rootCaseId) }

    @PostMapping("/api/cases/{caseId}/workspace/refresh")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun refresh(@PathVariable caseId: UUID): CaseWorkspaceView {
        val root = roots.resolve(caseId)
        root.binding?.let { status.refresh(it, root.repositoryPath.toAbsolutePath().normalize()) }
        return get(caseId)
    }

    @PostMapping("/api/cases/{caseId}/workspace/retry")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun retry(@PathVariable caseId: UUID, @RequestBody(required = false) request: WorkspaceRetryRequest?): CaseWorkspaceView {
        if (request?.acknowledgeSetupReplay == true) lifecycle.acknowledgeSetup(caseId) else lifecycle.retry(caseId)
        return get(caseId)
    }

    @PostMapping("/api/cases/{caseId}/workspace/recover")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun recover(@PathVariable caseId: UUID): CaseWorkspaceView {
        caseService.recoverWorkspaceCase(caseId)
        return get(caseId)
    }

    private fun canRead(caseId: UUID): Boolean = permissions.hasPermission(
        users.getCurrentUser().id.toString(), EntityType.CASE, caseId.toString(), Action.READ,
    )
}

data class WorkspaceRetryRequest(val acknowledgeSetupReplay: Boolean = false)

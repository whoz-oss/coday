package io.whozoss.agentos.git

import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.caseEvent.ParticipatingAgent
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.user.UserService
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class ExchangeEnvironment(
    val equipped: Boolean,
    val status: String? = null,
    val path: String? = null,
    val branch: String? = null,
    val changes: ExchangeGitChanges? = null,
    val git: GitWorkspaceSummary? = null,
    val agents: List<ParticipatingAgent> = emptyList(),
    val error: String? = null,
)

@RestController
@RequestMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
class ExchangeEnvironmentController(
    private val roots: ExchangeRootResolver,
    private val status: GitWorkspaceStatusService,
    private val diffs: ExchangeGitDiff,
    private val cases: CaseRepository,
    private val events: CaseEventRepository,
    private val users: UserService,
    private val permissions: PermissionService,
) {
    @GetMapping("/api/cases/{caseId}/exchange/environment")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    fun caseEnvironment(@PathVariable caseId: UUID): ExchangeEnvironment {
        val root = roots.resolve(caseId)
        val rootCase = roots.resolveRootCase(cases.findByIds(listOf(caseId)).firstOrNull() ?: throw ResourceNotFoundException("Case not found"))
        val userId = users.getCurrentUser().id.toString()
        // A shared worktree does not confer permission to inspect private sibling conversations.
        val familyIds = roots.familyMembers(rootCase)
            .filter { permissions.hasPermission(userId, EntityType.CASE, it.id.toString(), Action.READ) }.map { it.id }
        val agents = events.participatingAgents(familyIds)
        val binding = root.binding ?: return ExchangeEnvironment(false, agents = agents)
        val view = ExchangeEnvironment(true, binding.status.name, root.repositoryPath.toAbsolutePath().normalize().toString(), git = status.summary(binding), agents = agents)
        if (binding.status != CaseResourceStatus.READY) return view
        val observed = inspect(view, caseTarget(caseId))
        // The forge monitor is asynchronous; never attach its previous branch's PR to a new HEAD.
        return observed.copy(git = observed.git.takeIf { observed.branch == binding.branchName })
    }

    @GetMapping("/api/cases/{caseId}/exchange/diff")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    fun caseDiff(@PathVariable caseId: UUID, @RequestParam path: String): ExchangeFileDiff = diffs.file(caseTarget(caseId), path)

    private fun inspect(view: ExchangeEnvironment, target: ExchangeGitTarget): ExchangeEnvironment = try {
        view.copy(branch = diffs.branch(target), changes = diffs.changes(target))
    } catch (e: Exception) {
        view.copy(error = "Cannot inspect Git changes. Retry shortly.")
    }

    private fun caseTarget(caseId: UUID): ExchangeGitTarget {
        val root = roots.resolve(caseId)
        val b = root.binding ?: throw ResourceNotFoundException("This case has no repository")
        if (b.status != CaseResourceStatus.READY) throw ConflictException("The worktree is not available yet")
        return ExchangeGitTarget(root.repositoryPath.toAbsolutePath().normalize(), status.worktreeGitDir(b), status.commonGitDir(b), status.settings(b).mainBranch, b.baseSha)
    }

}

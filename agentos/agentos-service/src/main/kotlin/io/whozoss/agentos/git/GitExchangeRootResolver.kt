package io.whozoss.agentos.git

import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exchange.ExchangeRootResolver
import io.whozoss.agentos.exchange.ResolvedExchangeRoot
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Resolves the directory a case's files actually live in.
 *
 * One seam for REST and for the agent tools, so a user and an agent looking at "the files of this
 * case" always see the same directory. Without it the two layers compute the path independently
 * and drift the moment a family is equipped.
 *
 * For an ordinary family this is the historic per-case directory. For an equipped family it is the
 * root case's Exchange directory — documents and repo/ — for every descendant, which is what makes grooming,
 * development and review sub-cases work on the same files and the same branch.
 *
 * Active only with `agentos.git.workspaces.enabled`, as the primary resolver; otherwise [io.whozoss.agentos.exchange.DefaultExchangeRootResolver]
 * serves every case.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "agentos.git.workspaces", name = ["enabled"], havingValue = "true")
class GitExchangeRootResolver(
    private val caseRepository: CaseRepository,
    private val bindingService: CaseResourceBindingService,
    private val exchangeStorageService: ExchangeStorageService,
) : ExchangeRootResolver {
    override fun resolve(case: Case): ResolvedExchangeRoot = resolveGit(case).exchange

    /** The family is found through the parent chain, so the case itself is read. */
    override fun resolve(
        caseId: UUID,
        namespaceId: UUID,
        caseCreatedAt: Instant,
    ): ResolvedExchangeRoot = resolve(requireCase(caseId))

    override fun <T> withFileMutation(
        case: Case,
        action: (ResolvedExchangeRoot) -> T,
    ): T {
        val git = resolveGit(case)
        val root = git.exchange
        val workspaceId = git.binding?.rootCaseId ?: return action(root)
        return WorkspaceLifecycleLocks.tryWithRoot(
            workspaceId,
            onBusy = { throw ConflictException("The workspace is busy; retry the file operation shortly") },
        ) {
            // Preparation or cleanup may have changed the case or its workspace since the first read.
            val current =
                caseRepository.findByIds(listOf(case.id)).firstOrNull()
                    ?: throw ResourceNotFoundException("Case not found: ${case.id}")
            val resolved = resolve(current)
            resolved.requireUsable()
            action(resolved)
        }
    }
    /**
     * Where [case]'s files live, and the workspace backing it when the family is equipped.
     */
    fun resolveGit(case: Case): GitExchangeRoot {
        val rootCase = resolveRootCase(case)
        val binding = bindingService.findByRootCaseId(rootCase.id)

        return when (binding) {
            null ->
                GitExchangeRoot(
                    path = exchangeStorageService.caseRoot(case.namespaceId, case.id, case.metadata.created),
                    binding = null,
                    ownerCaseId = case.id,
                )

            else ->
                GitExchangeRoot(
                    path =
                        exchangeStorageService.caseRoot(
                            rootCase.namespaceId,
                            rootCase.id,
                            rootCase.metadata.created,
                        ),
                    binding = binding,
                    ownerCaseId = rootCase.id,
                )
        }
    }

    /** Convenience for callers holding only an id. */
    fun resolveGit(caseId: UUID): GitExchangeRoot = resolveGit(requireCase(caseId))

    /**
     * Walk up to the technical root of the family (`parentCaseId == null`).
     *
     * Ancestors are read **including soft-deleted ones**: deleting a parent does not delete its
     * children, and a family whose root was removed must keep resolving to the same directory
     * rather than having every descendant silently fall back to its own — which would strand the
     * shared worktree while agents kept writing elsewhere.
     *
     * The walk is bounded: a cycle introduced by a bad write must not spin here.
     */
    fun resolveRootCase(case: Case): Case {
        var current = case
        var hops = 0
        while (true) {
            val parentId = current.parentCaseId ?: return current
            if (++hops > MAX_ANCESTOR_HOPS) {
                throw ConflictException("Invalid case ancestry: cycle or excessive depth")
            }
            current =
                caseRepository.findByIds(listOf(parentId), withRemoved = true).firstOrNull()
                    ?: throw ConflictException("The parent case $parentId is unavailable")
            if (current.namespaceId != case.namespaceId) {
                throw ConflictException("Invalid case ancestry: it crosses namespaces")
            }
        }
    }

    private fun requireCase(caseId: UUID): Case =
        caseRepository.findByIds(listOf(caseId), withRemoved = true).firstOrNull()
            ?: throw ResourceNotFoundException("Case not found: $caseId")

    companion object : KLogging() {
        /**
         * Generous bound relative to the delegation depth limit: the walk must tolerate a legal
         * hierarchy and only defend against a cycle.
         */
        private const val MAX_ANCESTOR_HOPS = 32
    }
}

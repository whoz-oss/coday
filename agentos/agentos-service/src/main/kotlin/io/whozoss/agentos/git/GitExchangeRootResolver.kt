package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exchange.ExchangeRootResolver
import io.whozoss.agentos.exchange.ResolvedExchangeRoot
import io.whozoss.agentos.exchange.ExchangeUnavailableException
import io.whozoss.agentos.exception.ConflictException
import mu.KLogging
import org.springframework.stereotype.Component
import java.nio.file.Path
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
 */
@Component
class GitExchangeRootResolver(
    private val caseRepository: CaseRepository,
    private val bindingService: CaseResourceBindingService,
    private val exchangeStorageService: ExchangeStorageService,
    private val objectMapper: ObjectMapper,
) : ExchangeRootResolver {
    override fun resolve(case: Case): ResolvedExchangeRoot = resolveGit(case).exchange

    override fun resolve(caseId: UUID): ResolvedExchangeRoot = resolve(requireCase(caseId))

    override fun <T> withCaseMutation(caseId: UUID, action: (ResolvedExchangeRoot) -> T): T {
        val root = resolve(caseId)
        val workspaceId = root.workspace?.id ?: return action(root)
        return WorkspaceLifecycleLocks.tryWithRoot(
            workspaceId,
            onBusy = { throw ConflictException("The workspace is busy; retry the file operation shortly") },
        ) {
            // Preparation/cleanup may have changed availability after the first resolution.
            val current = resolve(caseId)
            current.requireUsable()
            action(current)
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
                    supportDirectory = exchangeStorageService.workspaceSupportDirectory(rootCase.namespaceId, rootCase.id),
                    toolParameters = gitToolParameters(rootCase, binding),
                )
        }
    }

    /**
     * What the `GIT` tools need to work in this family's worktree, from the settings recorded when
     * the family was equipped. The administrative directory is pinned by name, so the tools never
     * trust the worktree's own `.git` pointer file, which an agent can rewrite.
     */
    private fun gitToolParameters(rootCase: Case, binding: CaseResourceBinding): Map<String, String> {
        val settings = binding.settingsJson?.let { objectMapper.readValue(it, GitRepositorySettings::class.java) }
            ?: return emptyMap()
        val common = exchangeStorageService.namespaceGitDirectory(rootCase.namespaceId).toAbsolutePath().normalize()
        return mapOf(
            "gitDir" to common.resolve("worktrees").resolve(rootCase.id.toString()).toString(),
            "commonGitDir" to common.toString(),
            "repositoryUrl" to settings.repositoryUrl,
            "mainBranch" to settings.mainBranch,
        )
    }

    /** Convenience for callers holding only an id. */
    fun resolveGit(caseId: UUID): GitExchangeRoot = resolveGit(requireCase(caseId))

    /** The Namespace Exchange root, containing shared documents only. */
    override fun resolveNamespaceRoot(namespaceId: UUID): Path = exchangeStorageService.namespaceRoot(namespaceId)

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
                throw ExchangeUnavailableException("Invalid case ancestry: cycle or excessive depth")
            }
            current =
                caseRepository.findByIds(listOf(parentId), withRemoved = true).firstOrNull()
                    ?: throw ExchangeUnavailableException("The parent case $parentId is unavailable")
            check(current.namespaceId == case.namespaceId) { "Case ancestry crosses namespaces" }
        }
    }

    /**
     * Read the namespace once, then follow parent ids in memory. Older cases need not have a
     * PARENT_OF edge, so using that graph edge alone could miss a surviving child during cleanup.
     * Unrelated families must not trigger individual ancestor queries on every environment poll.
     */
    fun familyMembers(rootCase: Case): List<Case> {
        val children = caseRepository.findIncludingRemovedByNamespace(rootCase.namespaceId)
            .filter { it.namespaceId == rootCase.namespaceId }.groupBy { it.parentCaseId }
        val pending = ArrayDeque<Case>()
        val visited = mutableSetOf<UUID>()
        val family = mutableListOf<Case>()
        pending.add(rootCase)
        while (pending.isNotEmpty()) {
            val member = pending.removeFirst()
            if (!visited.add(member.id)) continue
            family.add(member)
            children[member.id].orEmpty().forEach(pending::addLast)
        }
        return family
    }

    private fun requireCase(caseId: UUID): Case =
        caseRepository.findByIds(listOf(caseId), withRemoved = true).firstOrNull()
            ?: throw IllegalArgumentException("Case $caseId not found")

    companion object : KLogging() {
        /**
         * Generous bound relative to the delegation depth limit: the walk must tolerate a legal
         * hierarchy and only defend against a cycle.
         */
        private const val MAX_ANCESTOR_HOPS = 32
    }
}

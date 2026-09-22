package io.whozoss.agentos.git

import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Reads the Git association of a namespace.
 *
 * The single entry point every Git-aware code path uses to answer "is this namespace associated
 * with a repository, and how". It deliberately offers no way to ask the question through the
 * layered [IntegrationConfigService.findEffective] resolution.
 */
@Service
class GitRepositoryAssociationService(
    private val integrationConfigService: IntegrationConfigService,
    private val settingsFactory: GitRepositorySettingsFactory,
) {
    /**
     * The namespace's validated Git association, or null when it has none.
     *
     * A row that exists but does not validate raises rather than returning null. Silently
     * treating a broken association as "no Git" would create unequipped cases in a namespace
     * whose users expect a worktree, and the mistake would only surface much later, as a missing
     * working directory.
     *
     * @throws io.whozoss.agentos.exception.BadRequestException when the stored row is not a usable
     *   association.
     */
    fun findSettings(namespaceId: UUID): GitRepositorySettings? =
        integrationConfigService
            .findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE)
            ?.let { settingsFactory.fromConfig(it) }

    /** Whether the namespace has a repository associated at all. */
    fun isAssociated(namespaceId: UUID): Boolean = findSettings(namespaceId) != null

    /**
     * Whether a *new* root case in this namespace should be equipped with a detached
     * worktree.
     *
     * Only ever consulted when a root case is created; the answer is then persisted on that
     * family's binding. Turning the flag on later must not retro-equip existing families, and
     * turning it off must not strip equipped ones.
     */
    fun isAutoWorktreeEnabled(namespaceId: UUID): Boolean =
        findSettings(namespaceId)?.autoWorktreeForRootCases ?: false

    companion object : KLogging()
}

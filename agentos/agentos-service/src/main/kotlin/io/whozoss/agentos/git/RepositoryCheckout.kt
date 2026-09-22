package io.whozoss.agentos.git

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/**
 * The managed clone materialising a namespace's Git association.
 *
 * One active row per namespace. [repositoryUrl] and [mainBranch] are snapshots of the association
 * at the moment the clone was prepared, kept so a later change to the configuration can be detected
 * and refused rather than silently applied to a checkout that no longer matches it.
 *
 * ## No path field, on purpose
 *
 * The bare repository lives outside both Exchange roots; its location is derived from
 * [io.whozoss.agentos.exchange.ExchangeStorageService.namespaceGitDirectory] rather than persisted. Git
 * already records absolute paths internally (in `worktrees/<id>/gitdir` and in each worktree's
 * `.git` file) while the exchange mount is configured as a path that may be relative to the JVM
 * working directory; persisting a second, independent copy of the location would add a third
 * source of truth that drifts as soon as the mount, the working directory or the container path
 * changes. See `agentos/docs/git-workspaces.md`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RepositoryCheckout(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID,
    /** The `GIT_REPOSITORY` configuration row this checkout materialises. */
    val integrationConfigId: UUID,
    /** Snapshot of the associated remote, used to detect a configuration change. */
    val repositoryUrl: String,
    /** Snapshot of the configured main branch. */
    val mainBranch: String,
    val status: RepositoryCheckoutStatus = RepositoryCheckoutStatus.PREPARING,
    /** Last successful fetch of [mainBranch], used to decide whether a refresh is due. */
    val lastFetchedAt: Instant? = null,
    /** Operator-facing reason when [status] is [RepositoryCheckoutStatus.FAILED]; never a secret. */
    val failureReason: String? = null,
) : Entity {
    /** Whether this checkout still matches [settings], or was prepared for a different association. */
    fun matches(settings: GitRepositorySettings): Boolean =
        integrationConfigId == settings.configId &&
            repositoryUrl == settings.repositoryUrl &&
            mainBranch == settings.mainBranch
}

package io.whozoss.agentos.git

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * The Git association of a namespace, as the namespace settings screen sees it.
 *
 * A dedicated projection rather than the raw integration configuration: the screen needs the
 * preparation state of the checkout alongside the settings, and must never expose a secret — the
 * service account appears as an id, never as a token.
 */
@Schema(name = "NamespaceGit")
data class NamespaceGitResource(
    /**
     * False when the namespace has no repository associated. Every other field is then empty: null,
     * or false for [autoWorktreeForRootCases].
     */
    val associated: Boolean,
    val repositoryUrl: String? = null,
    val mainBranch: String? = null,
    /** UUID of the namespace-shared auth setting used for every Git operation. Never the secret. */
    val serviceAuthSettingId: UUID? = null,
    /** Whether a new root case gets a detached worktree. */
    val autoWorktreeForRootCases: Boolean = false,
    val setupCommand: String? = null,
    @Schema(description = "Preparation state of the namespace repository. Null while no clone has been attempted")
    val checkoutStatus: RepositoryCheckoutStatus? = null,
    /** Operator-facing reason when the checkout failed. Never a secret. */
    val checkoutFailureReason: String? = null,
    val lastFetchedAt: Instant? = null,
)

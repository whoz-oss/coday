package io.whozoss.agentos.git

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
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
    /** False when the namespace has no repository associated; every other field is then null. */
    val associated: Boolean,
    val repositoryUrl: String? = null,
    val mainBranch: String? = null,
    /** UUID of the namespace-shared auth setting used for every Git operation. Never the secret. */
    val serviceAuthSettingId: UUID? = null,
    /** `PREPARING`, `READY` or `FAILED`; null while no clone has been attempted. */
    val checkoutStatus: String? = null,
    /** Operator-facing reason when the checkout failed. Never a secret. */
    val checkoutFailureReason: String? = null,
    val lastFetchedAt: Instant? = null,
)

/**
 * Request body to associate a repository, or to change an existing association.
 *
 * Validation of the values themselves (URL scheme, branch name, auth-setting format) is applied
 * server-side by the same rules that govern reading an association, so a request that succeeds
 * yields a configuration the provisioner can actually use.
 */
@Schema(name = "NamespaceGitRequest")
data class NamespaceGitRequest(
    @field:NotBlank(message = "repositoryUrl is required")
    val repositoryUrl: String,
    /** Defaults to `main` when omitted. */
    val mainBranch: String? = null,
    @field:NotNull(message = "serviceAuthSettingId is required")
    val serviceAuthSettingId: UUID,
)

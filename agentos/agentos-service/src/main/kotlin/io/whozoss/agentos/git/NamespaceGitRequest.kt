package io.whozoss.agentos.git

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

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

package io.whozoss.agentos.git

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Schema(name = "GitWorkspacesConfiguration")
data class GitWorkspacesConfiguration(
    val enabled: Boolean,
)

/**
 * Exposes `agentos.git.workspaces.enabled` so clients skip the workspace endpoints, which
 * [CaseWorkspaceController] only registers when the feature is on. Always registered.
 */
@RestController
@RequestMapping("/api/git-workspaces-configuration", produces = [MediaType.APPLICATION_JSON_VALUE])
class GitWorkspacesConfigurationController(
    @Value("\${agentos.git.workspaces.enabled:false}") private val enabled: Boolean,
) {
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getGitWorkspacesConfiguration")
    fun getGitWorkspacesConfiguration(): GitWorkspacesConfiguration = GitWorkspacesConfiguration(enabled = enabled)
}

package io.whozoss.agentos.git

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
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
 * Tells clients whether the workspace routes exist, so they skip them when `agentos.git.workspaces.enabled`
 * is off. Always registered. The answer is the presence of [CaseWorkspaceController] itself rather than a
 * second reading of the property, so it cannot disagree with the registered routes.
 */
@RestController
@RequestMapping("/api/git-workspaces-configuration", produces = [MediaType.APPLICATION_JSON_VALUE])
class GitWorkspacesConfigurationController(
    /** Null without `agentos.git.workspaces.enabled`: the workspace routes are then not registered. */
    private val workspaceRoutes: CaseWorkspaceController?,
) {
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getGitWorkspacesConfiguration")
    fun getGitWorkspacesConfiguration(): GitWorkspacesConfiguration =
        GitWorkspacesConfiguration(enabled = workspaceRoutes != null)
}

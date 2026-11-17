package io.whozoss.agentos.configFile

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.Caller
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Shows which configs of a namespace come from its `configPath` files, which live only in the
 * database, and which database copies mask a file (issue #1673).
 *
 * The list needs only read access to the namespace: it names configs the members already see. A diff
 * shows a config in its file format, like the YAML export, so it requires the export's permission:
 * WRITE on the config.
 */
@RestController
class ConfigFileController(
    private val configFileService: ConfigFileService,
    private val integrationConfigService: IntegrationConfigService,
    private val userService: UserService,
) {
    @Operation(
        summary = "List the config files of a namespace",
        description =
            "One entry per agent, integration, skill and prompt file of the namespace's configPath, with " +
                "whether AgentOS reads the file (FILE) or a database copy of the same name that masks it " +
                "(OVERRIDDEN). Empty when the namespace has no configPath.",
    )
    @GetMapping("/api/namespaces/{namespaceId}/config-files", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun listConfigFiles(
        @PathVariable namespaceId: UUID,
    ): List<ConfigFileDto> = configFileService.list(namespaceId)

    @Operation(summary = "Compare an agent that overrides a file with that file")
    @GetMapping("/api/agent-configs/{id}/file-diff", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    @HideOnAccessDenied
    fun agentConfigFileDiff(
        @PathVariable id: UUID,
    ): ConfigFileDiffDto = configFileService.diff(FilesystemEntityKind.AGENT, id)

    @Operation(summary = "Compare an integration that overrides a file with that file")
    @GetMapping("/api/integration-configs/{id}/file-diff", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @HideOnAccessDenied
    fun integrationConfigFileDiff(
        @PathVariable id: UUID,
    ): ConfigFileDiffDto {
        // Integrations check their permissions in the service, like their export does.
        val caller = userService.getCurrentUser().let { Caller.Human(it.id, it.isAdmin) }
        integrationConfigService.getByIdForCaller(caller, id, Action.WRITE)
        return configFileService.diff(FilesystemEntityKind.INTEGRATION, id)
    }

    @Operation(summary = "Compare a skill that overrides a file with that file")
    @GetMapping("/api/skills/{id}/file-diff", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Skill', 'WRITE')")
    @HideOnAccessDenied
    fun skillFileDiff(
        @PathVariable id: UUID,
    ): ConfigFileDiffDto = configFileService.diff(FilesystemEntityKind.SKILL, id)

    @Operation(summary = "Compare a prompt that overrides a file with that file")
    @GetMapping("/api/prompts/{id}/file-diff", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
    @HideOnAccessDenied
    fun promptFileDiff(
        @PathVariable id: UUID,
    ): ConfigFileDiffDto = configFileService.diff(FilesystemEntityKind.PROMPT, id)
}

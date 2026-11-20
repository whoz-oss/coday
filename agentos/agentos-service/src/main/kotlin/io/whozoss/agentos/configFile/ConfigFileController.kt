package io.whozoss.agentos.configFile

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.Caller
import io.whozoss.agentos.plugin.filesystem.FilesystemEntityKind
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Shows which configs of a namespace come from its `configPath` files, which live only in the
 * database, and which database copies mask a file; writes such a copy back to its file, or discards it
 * (issue #1673).
 *
 * The list needs only read access to the namespace: it names configs the members already see. A diff and
 * a download show a config in its file format, like the YAML export, so they require the export's
 * permission, WRITE on the config, and so does writing it to its file. Discarding the copy deletes it:
 * DELETE on the config.
 *
 * Integrations check their permissions in the service, like their export does; the other kinds declare
 * them on the endpoint.
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

    // ---------------------------------------------------------------- agents

    @Operation(summary = "Compare an agent that overrides a file with that file")
    @GetMapping("/api/agent-configs/{id}/file-diff", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    @HideOnAccessDenied
    fun agentConfigFileDiff(
        @PathVariable id: UUID,
    ): ConfigFileDiffDto = configFileService.diff(FilesystemEntityKind.AGENT, id)

    @Operation(summary = "Download an agent that overrides a file, as that file")
    @GetMapping("/api/agent-configs/{id}/file", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    @HideOnAccessDenied
    fun agentConfigFile(
        @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> = attachment(configFileService.download(FilesystemEntityKind.AGENT, id))

    @Operation(summary = "Write an agent that overrides a file to that file, then delete the database copy")
    @PostMapping("/api/agent-configs/{id}/write-to-file", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'WRITE')")
    @HideOnAccessDenied
    fun agentConfigWriteToFile(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ConfigFileWriteRequestDto,
    ): ConfigFileWriteResultDto = configFileService.writeToFile(FilesystemEntityKind.AGENT, id, request.expectedFileHash)

    @Operation(summary = "Delete the database copy of an agent that overrides a file: AgentOS reads the file again")
    @PostMapping("/api/agent-configs/{id}/discard-copy", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AgentConfig', 'DELETE')")
    @HideOnAccessDenied
    fun agentConfigDiscardCopy(
        @PathVariable id: UUID,
    ): ConfigFileWriteResultDto = configFileService.discard(FilesystemEntityKind.AGENT, id)

    // ---------------------------------------------------------------- integrations

    @Operation(summary = "Compare an integration that overrides a file with that file")
    @GetMapping("/api/integration-configs/{id}/file-diff", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @HideOnAccessDenied
    fun integrationConfigFileDiff(
        @PathVariable id: UUID,
    ): ConfigFileDiffDto {
        requireIntegration(id, Action.WRITE)
        return configFileService.diff(FilesystemEntityKind.INTEGRATION, id)
    }

    @Operation(summary = "Download an integration that overrides a file, as that file")
    @GetMapping("/api/integration-configs/{id}/file", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("isAuthenticated()")
    @HideOnAccessDenied
    fun integrationConfigFile(
        @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> {
        requireIntegration(id, Action.WRITE)
        return attachment(configFileService.download(FilesystemEntityKind.INTEGRATION, id))
    }

    @Operation(summary = "Write an integration that overrides a file to that file, then delete the database copy")
    @PostMapping("/api/integration-configs/{id}/write-to-file", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @HideOnAccessDenied
    fun integrationConfigWriteToFile(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ConfigFileWriteRequestDto,
    ): ConfigFileWriteResultDto {
        requireIntegration(id, Action.WRITE)
        return configFileService.writeToFile(FilesystemEntityKind.INTEGRATION, id, request.expectedFileHash)
    }

    @Operation(summary = "Delete the database copy of an integration that overrides a file: AgentOS reads the file again")
    @PostMapping("/api/integration-configs/{id}/discard-copy", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @HideOnAccessDenied
    fun integrationConfigDiscardCopy(
        @PathVariable id: UUID,
    ): ConfigFileWriteResultDto {
        requireIntegration(id, Action.DELETE)
        return configFileService.discard(FilesystemEntityKind.INTEGRATION, id)
    }

    // ---------------------------------------------------------------- skills

    @Operation(summary = "Compare a skill that overrides a file with that file")
    @GetMapping("/api/skills/{id}/file-diff", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Skill', 'WRITE')")
    @HideOnAccessDenied
    fun skillFileDiff(
        @PathVariable id: UUID,
    ): ConfigFileDiffDto = configFileService.diff(FilesystemEntityKind.SKILL, id)

    @Operation(summary = "Download a skill that overrides a file, as a zip of its folder")
    @GetMapping("/api/skills/{id}/file", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasPermission(#id, 'Skill', 'WRITE')")
    @HideOnAccessDenied
    fun skillFile(
        @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> = attachment(configFileService.download(FilesystemEntityKind.SKILL, id))

    @Operation(summary = "Write a skill that overrides a file to its folder, then delete the database copy")
    @PostMapping("/api/skills/{id}/write-to-file", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Skill', 'WRITE')")
    @HideOnAccessDenied
    fun skillWriteToFile(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ConfigFileWriteRequestDto,
    ): ConfigFileWriteResultDto = configFileService.writeToFile(FilesystemEntityKind.SKILL, id, request.expectedFileHash)

    @Operation(summary = "Delete the database copy of a skill that overrides a file: AgentOS reads the file again")
    @PostMapping("/api/skills/{id}/discard-copy", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Skill', 'DELETE')")
    @HideOnAccessDenied
    fun skillDiscardCopy(
        @PathVariable id: UUID,
    ): ConfigFileWriteResultDto = configFileService.discard(FilesystemEntityKind.SKILL, id)

    // ---------------------------------------------------------------- prompts

    @Operation(summary = "Compare a prompt that overrides a file with that file")
    @GetMapping("/api/prompts/{id}/file-diff", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
    @HideOnAccessDenied
    fun promptFileDiff(
        @PathVariable id: UUID,
    ): ConfigFileDiffDto = configFileService.diff(FilesystemEntityKind.PROMPT, id)

    @Operation(summary = "Download a prompt that overrides a file, as that file")
    @GetMapping("/api/prompts/{id}/file", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
    @HideOnAccessDenied
    fun promptFile(
        @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> = attachment(configFileService.download(FilesystemEntityKind.PROMPT, id))

    @Operation(summary = "Write a prompt that overrides a file to that file, then delete the database copy")
    @PostMapping("/api/prompts/{id}/write-to-file", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
    @HideOnAccessDenied
    fun promptWriteToFile(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ConfigFileWriteRequestDto,
    ): ConfigFileWriteResultDto = configFileService.writeToFile(FilesystemEntityKind.PROMPT, id, request.expectedFileHash)

    @Operation(summary = "Delete the database copy of a prompt that overrides a file: AgentOS reads the file again")
    @PostMapping("/api/prompts/{id}/discard-copy", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'DELETE')")
    @HideOnAccessDenied
    fun promptDiscardCopy(
        @PathVariable id: UUID,
    ): ConfigFileWriteResultDto = configFileService.discard(FilesystemEntityKind.PROMPT, id)

    private fun requireIntegration(
        id: UUID,
        action: Action,
    ) {
        val caller = userService.getCurrentUser().let { Caller.Human(it.id, it.isAdmin) }
        integrationConfigService.getByIdForCaller(caller, id, action)
    }

    private fun attachment(download: ConfigFileDownload): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.fileName).build().toString())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(download.content)
}

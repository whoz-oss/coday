package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import org.springframework.beans.factory.annotation.Qualifier
import io.whozoss.agentos.entity.ScopeParams
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.Caller
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigApi
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * Unified REST API for [IntegrationConfig] entities — covers the **four scopes**
 * (platform, NS-shared, user × namespace, user-global) under a single set of routes.
 *
 * Authorization and scope rules (platform guard, user-scope type denial, namespace
 * permission checks, mass-assignment guards) are fully enforced by
 * [IntegrationConfigService]'s `*ForCaller` methods. This controller only maps HTTP
 * requests to that authorized surface and decides how a refusal is rendered:
 * [HideOnAccessDenied] on [getById], [update], [delete], and [export] converts an
 * [org.springframework.security.access.AccessDeniedException] into 404 instead of 403.
 */
@RestController
@RequestMapping(
    "/api/integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationConfigController(
    private val integrationConfigService: IntegrationConfigService,
    private val userService: UserService,
    @Qualifier("yamlExportMapper") private val yamlExportMapper: ObjectMapper,
) : IntegrationConfigApi {

    @GetMapping("/{id}")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): IntegrationConfigDto {
        val caller = callerOf()
        return toDto(integrationConfigService.getByIdForCaller(caller, id))
    }

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<IntegrationConfigDto> {
        val caller = callerOf()
        return integrationConfigService.findByIdsForCaller(caller, request.ids, request.withRemoved).map(::toDto)
    }

    /**
     * Hard-break stub for the legacy `GET /by-parentId/{parentId}`. Hidden from
     * the OpenAPI spec so the SDK no longer surfaces `listByParentIntegrationConfig`.
     */
    @Hidden
    @GetMapping("/by-parentId/{parentId}")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<IntegrationConfigDto> =
        throw ResourceNotFoundException(
            "Endpoint removed; use GET /api/integration-configs?namespaceId=$parentId instead",
        )

    @Operation(
        summary = "List IntegrationConfigs by scope",
        description =
            "Scope is inferred from the query params :\n\n" +
                "| query                            | mode             | required permission                            |\n" +
                "|----------------------------------|------------------|------------------------------------------------|\n" +
                "| (no params)                      | all caller's     | authenticated                                  |\n" +
                "| `?namespaceId=none` (no userId)  | platform         | authenticated                                  |\n" +
                "| `?namespaceId=<uuid>`            | NS-shared        | READ on the namespace (empty list if missing)  |\n" +
                "| `?namespaceId=<uuid>&userId=me`  | user × namespace | authenticated                                  |\n" +
                "| `?namespaceId=none&userId=me`    | user-global      | authenticated                                  |\n" +
                "| `?userId=me` (no namespace)      | all caller's     | authenticated                                  |\n\n" +
                "`userId` accepts ONLY the literal sentinel `me` \u2014 a UUID returns 400. " +
                "`namespaceId=none` is the sentinel for `namespaceId IS NULL`.",
    )
    @GetMapping
    override fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
    ): List<IntegrationConfigDto> {
        val caller = callerOf()
        ScopeParams.validateUserParam(userId)
        val resolvedNs = ScopeParams.parseNamespaceParam(namespaceId)
        return integrationConfigService
            .findFilteredForCaller(
                caller = caller,
                namespaceId = resolvedNs,
                namespaceIsNone = namespaceId?.equals(ScopeParams.NONE, ignoreCase = true) == true,
                userRequested = userId != null,
            ).map(::toDto)
    }

    @Operation(
        summary = "Create an IntegrationConfig",
        description =
            "Scope is inferred implicitly from the body's `(namespaceId, userId)` pair :\n\n" +
                "| body.namespaceId | body.userId        | scope         | required permission                  |\n" +
                "|------------------|--------------------|---------------|--------------------------------------|\n" +
                "| null             | null               | platform      | Super Admin only                     |\n" +
                "| present          | null               | NS-shared     | WRITE on the namespace               |\n" +
                "| null             | <currentUser.id>   | user-global   | authenticated only                   |\n" +
                "| present          | <currentUser.id>   | user×namespace| READ on the namespace                |\n\n" +
                "`body.userId` (when supplied) MUST equal the authenticated user's id. " +
                "A `namespaceId` that does not exist returns 404. " +
                "Integration types listed in `agentos.integrations.user-scope-denied-types` " +
                "(default `HTTP_API`, `MCP_STDIO`, `MCP_HTTP`, `GIT`) cannot be created in either user scope (403).",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: IntegrationConfigDto,
    ): IntegrationConfigDto {
        val caller = callerOf()
        val entity = IntegrationConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            userId = resource.userId,
            name = resource.name,
            integrationType = resource.integrationType,
            description = resource.description,
            parameters = resource.parameters,
            authSettingName = resource.authSettingName,
            autoGrant = resource.autoGrant,
        )
        return toDto(integrationConfigService.createForCaller(caller, entity))
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: IntegrationConfigDto,
    ): IntegrationConfigDto {
        val caller = callerOf()
        val changes = IntegrationConfigUpdate(
            name = resource.name,
            description = resource.description,
            parameters = resource.parameters,
            authSettingName = resource.authSettingName,
            autoGrant = resource.autoGrant,
        )
        return toDto(integrationConfigService.updateForCaller(caller, id, changes))
    }

    @DeleteMapping("/{id}")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) {
        val caller = callerOf()
        integrationConfigService.deleteForCaller(caller, id)
    }

    @Operation(
        summary = "Export an IntegrationConfig as a YAML file",
        description =
            "Returns the integration config as a downloadable YAML file, ready to be placed in " +
                "the namespace `integrations/` directory under `configPath`. " +
                "Only the fields meaningful in a filesystem config are included: " +
                "`name`, `integrationType`, `description`, `parameters`. " +
                "Scope metadata (`id`, `namespaceId`, `userId`) is intentionally omitted. " +
                "**parameters are exported in clear text** — requires WRITE permission on the entity.",
    )
    @GetMapping("/{id}/export", produces = [MediaType.APPLICATION_YAML_VALUE])
    @HideOnAccessDenied
    fun export(
        @PathVariable id: UUID,
    ): ResponseEntity<String> {
        val caller = callerOf()
        val entity = integrationConfigService.getByIdForCaller(caller, id, Action.WRITE)
        val yaml = PORTABILITY_COMMENT + yamlExportMapper.writeValueAsString(toExportModel(entity))
        val filename = "${entity.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}.yaml"
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType(MediaType.APPLICATION_YAML_VALUE))
            .body(yaml)
    }

    private fun callerOf(): Caller = userService.getCurrentUser().let { Caller.Human(it.id, it.isAdmin) }

    companion object : KLogging() {
        /**
         * Comment block prefixed to every exported YAML file, explaining how to make the
         * exported `parameters` paths portable across developer machines.
         *
         * Paths written through the UI/API are absolute and specific to the machine that
         * entered them — there is no reverse substitution of `{{NAMESPACE_CONFIG_PATH}}` on
         * export (the token is a one-way, load-time replacement performed by
         * [io.whozoss.agentos.integrationConfig.FilesystemIntegrationConfigRepository] for files
         * placed under `<namespace.configPath>/integrations/`; configs created via the API are
         * not resolved through that path). Each line is a valid YAML comment, so the file remains
         * directly reloadable without edits.
         */
        private val PORTABILITY_COMMENT: String =
            """
            |# This file was exported from a persisted IntegrationConfig. Paths under `parameters`
            |# (e.g. workingDirectory, rootPath, cwd, args) are absolute and specific to the machine
            |# that entered them.
            |#
            |# To make this file portable across developer machines, replace the part of each path
            |# that matches the namespace's configPath with the token {{NAMESPACE_CONFIG_PATH}}.
            |# It is resolved automatically at load time, only for files placed in
            |# {configPath}/integrations/ (configs created through the API are not affected).
            |#
            |# Example: workingDirectory: /home/alice/repos/myproject
            |#       -> workingDirectory: "{{NAMESPACE_CONFIG_PATH}}/.."
            |#
            |# Relative path segments following the token are kept as-is, not normalised:
            |# "{{NAMESPACE_CONFIG_PATH}}/../scripts" works.
            |
            """.trimMargin()
    }
}

private fun toDto(entity: IntegrationConfig) =
    IntegrationConfigDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        name = entity.name,
        integrationType = entity.integrationType,
        description = entity.description,
        parameters = entity.parameters,
        authSettingName = entity.authSettingName,
        autoGrant = entity.autoGrant,
    )

/**
 * Produces the filesystem-ready export model from a persisted [IntegrationConfig].
 *
 * Built explicitly via `buildMap` so that null/empty values are omitted without a
 * mapper-level inclusion policy — consistent with [AgentConfigController] and
 * [PromptController]. The [parameters] field is a [JsonNode] that the YAML mapper
 * serialises natively; a null value means no parameters were set, so the key is omitted.
 */
private fun toExportModel(entity: IntegrationConfig): Map<String, Any?> =
    buildMap {
        put("name", entity.name)
        put("integrationType", entity.integrationType)
        entity.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        entity.parameters?.let { put("parameters", it) }
        entity.authSettingName?.takeIf { it.isNotBlank() }?.let { put("authSettingName", it) }
        // Only emitted when set: `autoGrant: false` is the default, and writing it on every export
        // would add noise to files whose point is to be read and diffed by humans.
        if (entity.autoGrant) put("autoGrant", true)
    }

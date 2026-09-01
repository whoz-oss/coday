package io.whozoss.agentos.prompt

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import org.springframework.beans.factory.annotation.Qualifier
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.OverlayScopeAuthorizer
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.prompt.PromptApi
import io.whozoss.agentos.sdk.api.prompt.PromptDto
import io.whozoss.agentos.sdk.api.prompt.PromptEffectiveRequest
import io.whozoss.agentos.sdk.api.prompt.PromptParameterDto
import io.whozoss.agentos.sdk.api.prompt.PromptSearchRequest
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * REST API for [Prompt] entities at /api/prompts.
 *
 * Implements [PromptApi] so external consumers can declare a Feign client against
 * the SDK interface. Standard CRUD operations are delegated to [crud] explicitly.
 *
 * **Scope dispatch on POST** — inferred from `(body.namespaceId, body.userId)`:
 * - `(null, null)`   → platform (Super Admin only)
 * - `(ns, null)`     → namespace-scoped (WRITE on namespace)
 * - `(null, user)`   → user-global (authenticated only)
 * - `(ns, user)`     → user × namespace (READ on namespace)
 *
 * `body.userId` must equal the authenticated user’s id when supplied (mass-assignment guard).
 *
 * **Mass-assignment guard on PUT**: [namespaceId], [userId] and [agentConfigId] are
 * immutable post-create — preserved from the persisted entity.
 * Mutable fields: name, description, content, parameters, externalMetadata.
 *
 * **[search]** returns prompts at an exact scope level — no merge, no inheritance.
 * **[resolveEffective]** returns the merged set across the four overlay layers.
 *
 * **Why [search]/[resolveEffective]/[create] use [OverlayScopeAuthorizer] instead of `@PreAuthorize`**:
 * these three endpoints have no target id to evaluate a permission against — the request
 * body itself carries the `(namespaceId, userId)` scope that determines which check applies.
 * A `@PreAuthorize` SpEL expression can only ever produce 403 on refusal, but the
 * mass-assignment guard on `body.userId` must produce 400 (malformed request); and the
 * external-id resolution ([io.whozoss.agentos.entity.ExternalIdentifierResolver], a Neo4j
 * lookup) would have to run twice — once in SpEL to authorize, once in the method body to
 * use the resolved value.
 * [OverlayScopeAuthorizer] resolves once and throws the correctly-typed exception, so
 * `@PreAuthorize("isAuthenticated()")` remains only as the declarative floor on these three.
 */
@RestController
@RequestMapping(
    "/api/prompts",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class PromptController(
    private val promptService: PromptService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
    private val overlayScopeAuthorizer: OverlayScopeAuthorizer,
    @Qualifier("yamlExportMapper") private val yamlExportMapper: ObjectMapper,
) : PromptApi {
    private val crud =
        EntityCrudDelegate(
            service = promptService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.PROMPT,
            toResource = { toDto(it as Prompt) },
        )

    // -------------------------------------------------------------------------
    // Read endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Prompt', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): PromptDto = crud.getById(id)

    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<PromptDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @Operation(
        summary = "List Prompts at an exact scope level",
        description =
            "Returns prompts declared at a single exact scope level — no merge, no inheritance. " +
                "The `(namespaceId?, userId?)` combination in the body determines the level:\n\n" +
                "| namespaceId | userId   | level            | required permission        |\n" +
                "|-------------|----------|------------------|----------------------------|\n" +
                "| null        | null     | platform         | authenticated              |\n" +
                "| non-null    | null     | namespace-shared | READ on namespace          |\n" +
                "| null        | non-null | user-global      | authenticated              |\n" +
                "| non-null    | non-null | user×namespace   | READ on namespace          |\n\n" +
                "Optional `agentConfigIds` filter restricts results to prompts linked to those agents.",
    )
    @PostMapping(
        "/search",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun search(
        @Valid @RequestBody request: PromptSearchRequest,
    ): List<PromptDto> {
        val scope =
            overlayScopeAuthorizer.authorizeSearchOrThrow(
                pluralLabel = "prompts",
                namespaceId = request.namespaceId,
                namespaceExternalId = request.namespaceExternalId,
                userId = request.userId,
                userExternalId = request.userExternalId,
            )
        return promptService
            .findByScope(
                namespaceId = scope.namespaceId,
                userId = scope.userId,
                agentConfigIds = request.agentConfigIds,
            ).map(::toDto)
    }

    @Operation(
        summary = "Effective prompts for the authenticated user in a namespace",
        description =
            "Returns the resolved set of prompts accessible in the given namespace context, " +
                "scoped to the authenticated caller. " +
                "Merges platform, namespace-shared, user-global and user×namespace layers by name, " +
                "highest-priority layer wins. Optional `agentConfigId` filter applied post-resolution. " +
                "Requires READ on the namespace.",
    )
    @PostMapping(
        "/effective",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun resolveEffective(
        @Valid @RequestBody request: PromptEffectiveRequest,
    ): List<PromptDto> {
        val scope =
            overlayScopeAuthorizer.authorizeEffectiveOrThrow(
                pluralLabel = "prompts",
                namespaceId = request.namespaceId,
                namespaceExternalId = request.namespaceExternalId,
            )
        return promptService
            .findEffective(scope.namespaceId!!, scope.userId!!, request.agentConfigId)
            .map(::toDto)
    }

    // -------------------------------------------------------------------------
    // Write endpoints
    // -------------------------------------------------------------------------

    @Operation(
        summary = "Create a Prompt",
        description =
            "Scope is inferred from `(body.namespaceId, body.userId)`:\n\n" +
                "| body.namespaceId | body.userId      | scope           | required permission        |\n" +
                "|------------------|------------------|-----------------|----------------------------|\n" +
                "| null             | null             | platform        | Super Admin only           |\n" +
                "| present          | null             | namespace       | WRITE on the namespace     |\n" +
                "| null             | <currentUser.id> | user-global     | authenticated only         |\n" +
                "| present          | <currentUser.id> | user×namespace  | READ on the namespace      |\n\n" +
                "`body.userId` must equal the authenticated user\'s id when supplied. " +
                "A `namespaceId` that does not exist returns 404.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: PromptDto,
    ): PromptDto {
        // Phase 1 — validation local to Prompt (unrelated to scope authorization)
        if (resource.content.any { it.isBlank() }) {
            throw BadRequestException("content elements must not be blank")
        }

        // Phase 2 — mass-assignment guard + scope dispatch + authorization
        val scope =
            overlayScopeAuthorizer.authorizeCreateOrThrow(
                entityLabel = "Prompt",
                requestedNamespaceId = resource.namespaceId,
                requestedUserId = resource.userId,
            )

        // Phase 3 — namespace existence check (deferred after authz, anti-enumeration)
        if (scope.namespaceId != null && namespaceService.findById(scope.namespaceId) == null) {
            throw ResourceNotFoundException("Namespace not found: ${scope.namespaceId}")
        }

        val target =
            Prompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = scope.namespaceId,
                userId = scope.userId,
                agentConfigId = resource.agentConfigId,
                name = resource.name,
                description = resource.description,
                content = resource.content,
                parameters = resource.parameters.map { it.toDomain() },
                externalMetadata = resource.externalMetadata,
            )
        return toDto(promptService.create(target))
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: PromptDto,
    ): PromptDto {
        val existing =
            promptService.findById(id)
                ?: throw ResourceNotFoundException("Prompt not found: $id")
        return toDto(promptService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Prompt', 'DELETE')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) {
        promptService.findById(id)
            ?: throw ResourceNotFoundException("Prompt not found: $id")
        crud.delete(id)
    }

    @Operation(
        summary = "Export a Prompt as a YAML file",
        description =
            "Returns the prompt as a downloadable YAML file, ready to be placed in " +
                "the namespace `prompts/` directory under `configPath`. " +
                "Only the fields meaningful in a filesystem prompt are included: " +
                "`name`, `description`, `content`, `parameters`. " +
                "Scope metadata (`id`, `namespaceId`, `userId`, `externalMetadata`) and audit fields " +
                "are intentionally omitted. " +
                "**`agentConfigId` is also omitted and, if set, this is a real loss of information**: " +
                "`FilesystemPromptRepository` deliberately does not support linking a file-backed prompt " +
                "to an AgentConfig (YAGNI \u2014 a file can only carry a name, and the need is already covered " +
                "by an `@agentName` prefix in the prompt's own `content`). If the exported prompt targets " +
                "an agent, add `@agentName` at the start of the content to preserve that targeting in the file.",
    )
    @GetMapping("/{id}/export", produces = [MediaType.APPLICATION_YAML_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
    @HideOnAccessDenied
    fun export(
        @PathVariable id: UUID,
    ): ResponseEntity<String> {
        val entity =
            promptService.findById(id)
                ?: throw ResourceNotFoundException("Prompt not found: $id")
        val yaml = yamlExportMapper.writeValueAsString(toExportModel(entity))
        val filename = entity.name.lowercase().replace(Regex("[^a-z0-9]+"), "-") + ".yaml"
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType(MediaType.APPLICATION_YAML_VALUE))
            .body(yaml)
    }

    private fun toDomainForUpdate(
        resource: PromptDto,
        existing: Prompt,
    ): Prompt =
        existing.copy(
            // Immutable: namespaceId, userId, agentConfigId
            name = resource.name,
            description = resource.description,
            content = resource.content,
            parameters = resource.parameters.map { it.toDomain() },
            externalMetadata = resource.externalMetadata,
        )

    private fun PromptParameterDto.toDomain(): PromptParameter =
        PromptParameter(
            name = name,
            description = description,
            defaultValue = defaultValue,
        )

    companion object : KLogging()
}

internal fun toDto(entity: Prompt): PromptDto =
    PromptDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        agentConfigId = entity.agentConfigId,
        name = entity.name,
        description = entity.description,
        content = entity.content,
        parameters =
            entity.parameters.map { p ->
                PromptParameterDto(
                    name = p.name,
                    description = p.description,
                    defaultValue = p.defaultValue,
                )
            },
        externalMetadata = entity.externalMetadata,
        createdBy = entity.metadata.createdBy,
        createdOn = entity.metadata.created,
        updatedBy = entity.metadata.modifiedBy,
        updatedOn = entity.metadata.modified,
    )

/**
 * Produces the filesystem-ready export model from a persisted [Prompt].
 *
 * Only the fields [FilesystemPromptRepository.parseYamlFile] reads are included: `name`,
 * `description`, `content`, `parameters`. Scope and persistence artefacts (`id`, `namespaceId`,
 * `userId`, `externalMetadata`, all audit fields) are intentionally excluded — they have no
 * meaning in a standalone YAML file.
 *
 * **`agentConfigId` is deliberately excluded, even when set.** [FilesystemPromptRepository] does
 * not support a filesystem prompt linking to an [io.whozoss.agentos.agentConfig.AgentConfig] (see
 * its KDoc: YAGNI — a file can only declare a name, and the loader would silently ignore an
 * `agentConfigId` field since [FilesystemPromptRepository]'s `PromptYamlModel` has no such
 * property). Exporting the raw UUID would therefore be worse than omitting it: a value with no
 * meaning outside this database, silently dropped on import. The targeting this field expresses
 * can be preserved manually by the user, prefixing the exported `content` with `@agentName`,
 * which [io.whozoss.agentos.caseFlow.CaseServiceImpl] already resolves at prompt-expansion time.
 *
 * The model is built explicitly via `buildMap` rather than through a mapper-level inclusion
 * policy (`NON_EMPTY`/`NON_NULL`) so that [PromptParameter.defaultValue] survives export even
 * when it is the empty string — a legitimate, meaningful value (a free-form optional parameter)
 * that a global `NON_EMPTY` policy would strip. See [PromptController.YAML_MAPPER] KDoc.
 */
private fun toExportModel(entity: Prompt): Map<String, Any?> =
    buildMap {
        put("name", entity.name)
        entity.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        put("content", entity.content)
        entity.parameters.takeIf { it.isNotEmpty() }?.let { params ->
            put("parameters", params.map { it.toExportModel() })
        }
    }

private fun PromptParameter.toExportModel(): Map<String, Any?> =
    buildMap {
        put("name", name)
        description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        put("defaultValue", defaultValue)
    }

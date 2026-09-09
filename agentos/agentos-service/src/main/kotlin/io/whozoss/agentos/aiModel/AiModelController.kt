package io.whozoss.agentos.aiModel

import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.api.aiProvider.AiModelApi
import io.whozoss.agentos.sdk.api.aiProvider.AiModelDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
 * REST API for [AiModel] entities. Implements [AiModelApi] so external consumers
 * can declare a Feign client against the SDK interface.
 *
 * Standard CRUD operations are delegated to [crud] explicitly by name.
 */
@RestController
@RequestMapping(
    "/api/ai-models",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AiModelController(
    private val aiModelService: AiModelService,
    userService: UserService,
    permissionService: PermissionService,
) : AiModelApi {
    private val crud =
        EntityCrudDelegate(
            service = aiModelService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.AI_MODEL,
            toResource = { toDto(it as AiModel) },
            toDomain = { resource ->
                AiModel(
                    metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
                    aiProviderId = resource.aiProviderId!!,
                    namespaceId = null, // server-resolved from parent AiProvider
                    userId = resource.userId,
                    apiModelName = resource.apiModelName,
                    description = resource.description,
                    alias = resource.alias,
                    priority = resource.priority,
                    temperature = resource.temperature,
                    maxTokens = resource.maxTokens,
                )
            },
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiModel', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): AiModelDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<AiModelDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("@aiModelGuard.canListByProvider(#parentId)")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<AiModelDto> = crud.listByParent(parentId)

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("@aiModelGuard.canCreate(#resource)")
    override fun create(
        @Valid @RequestBody resource: AiModelDto,
    ): AiModelDto = crud.create(resource)

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AiModel', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AiModelDto,
    ): AiModelDto {
        val existing = aiModelService.findById(id) ?: throw ResourceNotFoundException("AiModel not found: $id")
        return aiModelService
            .update(
                existing.copy(
                    apiModelName = resource.apiModelName,
                    description = resource.description,
                    alias = resource.alias,
                    priority = resource.priority,
                    temperature = resource.temperature,
                    maxTokens = resource.maxTokens,
                ),
            ).let(::toDto)
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiModel', 'DELETE')")
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    override fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<AiModelDto> = aiModelService.findByNamespaceId(namespaceId).map(::toDto)

    /**
     * GET /platform — list all platform-level model configs (namespaceId IS NULL).
     * Readable by any authenticated user; the models are owned by platform-level AiProviders.
     */
    @GetMapping("/platform")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    fun listPlatformLevel(): List<AiModelDto> = aiModelService.findPlatformLevel().map { toDto(it) }

    companion object : KLogging()
}

private fun toDto(entity: AiModel) =
    AiModelDto(
        id = entity.metadata.id,
        aiProviderId = entity.aiProviderId,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        apiModelName = entity.apiModelName,
        description = entity.description,
        alias = entity.alias,
        priority = entity.priority,
        temperature = entity.temperature,
        maxTokens = entity.maxTokens,
    )

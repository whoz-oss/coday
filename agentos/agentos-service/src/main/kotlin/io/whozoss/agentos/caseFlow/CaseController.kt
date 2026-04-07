package io.whozoss.agentos.caseFlow

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping(
    "/api/cases",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CaseController(
    private val caseService: CaseService,
    private val userService: UserService,
) : EntityController<Case, UUID, CaseResource>(caseService) {

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: Case): CaseResource =
        CaseResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            status = entity.status,
            title = entity.title,
        )

    override fun toDomain(resource: CaseResource): Case =
        Case(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            status = resource.status,
            title = resource.title ?: "",
        )

    // -------------------------------------------------------------------------
    // operationId overrides — keep OpenAPI names stable across regenerations
    // -------------------------------------------------------------------------

    @Operation(operationId = "getCaseById")
    override fun getById(id: UUID) = super.getById(id)

    @Operation(operationId = "getCasesByIds")
    override fun getByIds(ids: List<UUID>) = super.getByIds(ids)

    @Operation(operationId = "listCasesByParent")
    override fun listByParent(parentId: UUID) = super.listByParent(parentId)

    @Operation(operationId = "createCase")
    override fun create(@Valid @RequestBody resource: CaseResource) = super.create(resource)

    @Operation(operationId = "updateCase")
    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: CaseResource) = super.update(id, resource)

    @Operation(operationId = "deleteCase")
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    // -------------------------------------------------------------------------
    // Additional endpoints
    // -------------------------------------------------------------------------

    /** POST /api/cases/{caseId}/messages — add a user message to a running case. */
    @PostMapping("/{caseId}/messages")
    fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) {
        logger.info { "Adding message to case: $caseId" }
        val user = userService.getCurrentUser()
        val userActor = Actor(id = user.metadata.id.toString(), displayName = user.email, role = ActorRole.USER)
        caseService.addMessage(
            caseId = caseId,
            actor = userActor,
            content = listOf(MessageContent.Text(request.content)),
            answerToEventId = request.answerToEventId,
        )
        logger.info { "Message added to case: $caseId" }
    }

    /**
     * POST /api/cases/{caseId}/interrupt
     *
     * Interrupt the current agent turn and return the case to IDLE.
     * The runtime and SSE connection stay open — the user can send a corrective
     * message immediately. Use this when the agent is going in the wrong direction.
     */
    @PostMapping("/{caseId}/interrupt")
    fun interruptCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Interrupting case: $caseId" }
        caseService.interruptCase(caseId)
        logger.info { "Case interrupted: $caseId" }
    }

    /** POST /api/cases/{caseId}/kill — permanently terminate a case and evict its runtime. */
    @PostMapping("/{caseId}/kill")
    fun killCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Killing case: $caseId" }
        caseService.killCase(caseId)
        logger.info { "Case killed: $caseId" }
    }

    companion object : KLogging()
}

data class AddMessageRequest(
    val content: String,
    val answerToEventId: UUID? = null,
)

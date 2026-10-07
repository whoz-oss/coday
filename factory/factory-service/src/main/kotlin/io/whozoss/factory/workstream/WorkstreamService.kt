package io.whozoss.factory.workstream

import io.whozoss.factory.error.BadRequestException
import io.whozoss.factory.error.ConflictException
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.web.FactoryCaller
import io.whozoss.factory.web.factoryError
import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.domain.WorkstreamStatus
import io.whozoss.factory.workstream.persistence.Neo4jWorkstreamRepository
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import io.whozoss.factory.workstream.web.UpdateWorkstreamRequest
import io.whozoss.factory.workstream.web.WorkstreamBounds
import org.springframework.stereotype.Service

/**
 * Business logic of the `/api/factory/workstreams` surface.
 *
 * The Kotlin control plane reads and writes workstream nodes through
 * [Neo4jWorkstreamRepository], always constrained by the caller's [TenantScope].
 * The registry is versioned: updates bump the entry [Workstream.revision] and
 * the aggregated projection derives a stable ETag from it.
 */
@Service
class WorkstreamService(
    private val repository: Neo4jWorkstreamRepository,
) {

    private val slugPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun list(scope: TenantScope): List<Map<String, Any?>> = repository.list(scope)

    /** The enriched registry view of [slug], or 404 when absent in [scope]. */
    fun get(scope: TenantScope, slug: String): Map<String, Any?> =
        repository.findById(scope, slug)
            ?: throw ResourceNotFoundException(
                "Le workstream '$slug' n'existe pas",
                mapOf("code" to "WORKSTREAM_NOT_FOUND"),
            )

    /** The domain registry entry of [slug], or `null` when absent in [scope]. */
    fun findDomain(scope: TenantScope, slug: String): Workstream? = repository.findDomain(scope, slug)

    fun create(scope: TenantScope, slug: String?, name: String?, status: String?): Map<String, Any?> =
        create(scope, CreateWorkstreamRequest(slug = slug, name = name, status = status))

    /**
     * Create a registry entry with the enriched (but optional) governance
     * fields. The slug uniqueness and shape rules are unchanged.
     */
    fun create(scope: TenantScope, request: CreateWorkstreamRequest): Map<String, Any?> {
        val slug = request.slug
        val name = request.name ?: request.title
        if (slug.isNullOrBlank() || name.isNullOrBlank()) {
            throw BadRequestException("slug et name sont requis", mapOf("code" to "INVALID_WORKSTREAM_REQUEST"))
        }
        if (!slugPattern.matches(slug)) {
            throw BadRequestException(
                "slug invalide : lettres minuscules, chiffres et tirets uniquement (ex: talent-portal)",
                mapOf("code" to "INVALID_WORKSTREAM_SLUG"),
            )
        }
        val status = request.status?.takeIf { it.isNotBlank() }?.let { WorkstreamStatus.fromDbValue(it) }
            ?: throw BadRequestException("status est requis", mapOf("code" to "INVALID_WORKSTREAM_REQUEST"))
        val allowedWorkflowTypes = validateAllowedWorkflowTypes(request.allowedWorkflowTypes)
        if (repository.findById(scope, slug) != null) {
            throw ConflictException("Le workstream '$slug' existe déjà", mapOf("code" to "WORKSTREAM_ALREADY_EXISTS"))
        }
        val created = repository.create(
            scope,
            Workstream(
                organizationId = scope.organizationId,
                workstreamId = slug,
                namespaceId = request.namespaceId?.takeIf { it.isNotBlank() },
                name = name,
                status = status,
                controllerAgentRef = request.controllerAgentRef?.takeIf { it.isNotBlank() },
                allowedWorkflowTypes = allowedWorkflowTypes,
                governancePolicyRef = request.governancePolicyRef?.takeIf { it.isNotBlank() },
            ),
        )
        return get(scope, created.workstreamId)
    }

    /**
     * Save a new revision of the registry entry: non-null request fields are
     * applied, the revision is bumped by one. [expectedRevision] (from the body
     * or the `If-Match` header) is the optimistic-locking precondition.
     *
     * @throws ResourceNotFoundException when the entry is absent in [scope];
     * @throws RevisionConflictException when [expectedRevision] is stale.
     */
    fun update(
        scope: TenantScope,
        slug: String,
        request: UpdateWorkstreamRequest,
        expectedRevision: Int? = request.expectedRevision,
    ): Map<String, Any?> {
        val current = repository.findDomain(scope, slug)
            ?: throw ResourceNotFoundException(
                "Le workstream '$slug' n'existe pas",
                mapOf("code" to "WORKSTREAM_NOT_FOUND"),
            )
        if (expectedRevision != null && expectedRevision != current.revision) {
            throw RevisionConflictException(
                "The expected revision is stale.",
                mapOf("code" to "REVISION_CONFLICT", "expectedRevision" to expectedRevision, "revision" to current.revision),
            )
        }
        val nextStatus = request.status?.takeIf { it.isNotBlank() }?.let { WorkstreamStatus.fromDbValue(it) }
            ?: current.status
        val next = current.copy(
            name = (request.name ?: request.title)?.takeIf { it.isNotBlank() } ?: current.name,
            status = nextStatus,
            namespaceId = request.namespaceId?.takeIf { it.isNotBlank() } ?: current.namespaceId,
            controllerAgentRef = request.controllerAgentRef?.takeIf { it.isNotBlank() } ?: current.controllerAgentRef,
            allowedWorkflowTypes = request.allowedWorkflowTypes?.let { validateAllowedWorkflowTypes(it) }
                ?: current.allowedWorkflowTypes,
            governancePolicyRef = request.governancePolicyRef?.takeIf { it.isNotBlank() } ?: current.governancePolicyRef,
        )
        val saved = repository.save(scope, next)
        return get(scope, saved.workstreamId)
    }

    /**
     * Trust-boundary enforcement: the path/body workstream identifier must be
     * the caller's trusted tenant workstream — never an untrusted input.
     */
    fun assertWithinWorkstream(caller: FactoryCaller, pathWorkstreamId: String) {
        if (pathWorkstreamId != caller.scope.workstreamId) {
            factoryError(
                403,
                "WORKSTREAM_BOUNDARY_VIOLATION",
                "The requested workstream '$pathWorkstreamId' is outside the caller's trusted workstream.",
                mapOf("workstreamId" to pathWorkstreamId),
            )
        }
    }

    private fun validateAllowedWorkflowTypes(raw: List<String>?): List<String> {
        val types = raw.orEmpty().map { it.trim() }
        if (types.size > WorkstreamBounds.MAX_LIMIT) {
            throw BadRequestException(
                "allowedWorkflowTypes ne peut pas dépasser ${WorkstreamBounds.MAX_LIMIT} entrées",
                mapOf("code" to "INVALID_WORKSTREAM_REQUEST"),
            )
        }
        if (types.any { it.isEmpty() || !slugPattern.matches(it) }) {
            throw BadRequestException(
                "allowedWorkflowTypes doit contenir des slugs non vides (minuscules, chiffres, tirets)",
                mapOf("code" to "INVALID_WORKFLOW_TYPE"),
            )
        }
        return types.distinct()
    }
}

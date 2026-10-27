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

    fun listByOrganization(organizationId: String): List<Map<String, Any?>> =
        repository.listByOrganization(organizationId)


    fun requireScope(organizationId: String, workstreamId: String): TenantScope {
        val scope = TenantScope(organizationId, workstreamId)
        if (repository.findDomain(scope, workstreamId) == null) {
            throw ResourceNotFoundException(
                "Le workstream '$workstreamId' n'existe pas",
                mapOf("code" to "WORKSTREAM_NOT_FOUND"),
            )
        }
        return scope
    }

    /** The enriched registry view of [slug], or 404 when absent in [scope]. */
    fun get(scope: TenantScope, slug: String): Map<String, Any?> =
        repository.findById(scope, slug)
            ?: throw ResourceNotFoundException(
                "Le workstream '$slug' n'existe pas",
                mapOf("code" to "WORKSTREAM_NOT_FOUND"),
            )

    /** The domain registry entry of [slug], or `null` when absent in [scope]. */
    fun findDomain(scope: TenantScope, slug: String): Workstream? = repository.findDomain(scope, slug)

    /**
     * Resolve the unique mapping in an organization, or initialize it for a first
     * launch. The deterministic UUID-derived slug plus Neo4j node id makes two
     * concurrent first launches converge without resetting an existing entry.
     */
    fun findOrInitializeForNamespace(organizationId: String, namespaceId: String): Workstream {
        val normalizedNamespace = normalizeOptionalNamespace(namespaceId)
            ?: throw BadRequestException("namespaceId is invalid", mapOf("code" to "INVALID_WORKSTREAM_NAMESPACE"))
        findByNamespace(organizationId, normalizedNamespace)?.let { return it }
        val slug = "namespace-$normalizedNamespace"
        return try {
            val initialized = repository.mergeInitialNamespaceMapping(
                organizationId,
                slug,
                normalizedNamespace,
                "Namespace $normalizedNamespace",
            )
            if (initialized.organizationId != organizationId || initialized.namespaceId != normalizedNamespace) {
                throw namespaceConflict(normalizedNamespace)
            }
            initialized
        } catch (conflict: ConflictException) {
            // Another application instance may have won the unique namespace
            // constraint between the read and MERGE. Resolve that winner. A
            // business conflict without a winner remains explicit.
            findByNamespace(organizationId, normalizedNamespace) ?: throw conflict
        }
    }

    /** Pure organization-scoped namespace lookup. Never creates a workstream. */
    fun findByNamespace(organizationId: String, namespaceId: String): Workstream? {
        val normalizedNamespace = normalizeOptionalNamespace(namespaceId) ?: return null
        val matches = repository.findDomainsByNamespace(organizationId, normalizedNamespace)
        if (matches.size > 1) {
            throw ConflictException(
                "Multiple workstreams map to namespace '$normalizedNamespace'",
                mapOf("code" to "AMBIGUOUS_WORKSTREAM_NAMESPACE"),
            )
        }
        return matches.singleOrNull()
    }

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
        val namespaceId = requireNamespace(request.namespaceId)
        assertNamespaceAvailable(scope.organizationId, namespaceId)
        if (repository.findById(scope, slug) != null) {
            throw ConflictException("Le workstream '$slug' existe déjà", mapOf("code" to "WORKSTREAM_ALREADY_EXISTS"))
        }
        val created = repository.create(
            scope,
            Workstream(
                organizationId = scope.organizationId,
                workstreamId = slug,
                namespaceId = namespaceId,
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
        val requestedNamespace = normalizeOptionalNamespace(request.namespaceId)
        val nextNamespace = requestedNamespace ?: requireNamespace(current.namespaceId)
        assertNamespaceAvailable(scope.organizationId, nextNamespace, current.workstreamId)
        val next = current.copy(
            name = (request.name ?: request.title)?.takeIf { it.isNotBlank() } ?: current.name,
            status = nextStatus,
            namespaceId = nextNamespace,
            controllerAgentRef = request.controllerAgentRef?.takeIf { it.isNotBlank() } ?: current.controllerAgentRef,
            allowedWorkflowTypes = request.allowedWorkflowTypes?.let { validateAllowedWorkflowTypes(it) }
                ?: current.allowedWorkflowTypes,
            governancePolicyRef = request.governancePolicyRef?.takeIf { it.isNotBlank() } ?: current.governancePolicyRef,
        )
        val saved = repository.save(scope, next)
        return get(scope, saved.workstreamId)
    }

    /** Verify a path workstream against the already-resolved business scope. */
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

    /**
     * Resolve the single namespace configured for the caller's workstream.
     * This strict service boundary never accepts a caller-selected namespace
     * and never falls back to the historical `default` namespace.
     */
    fun requireConfiguredNamespace(scope: TenantScope): String {
        val workstream = findDomain(scope, scope.workstreamId)
            ?: factoryError(403, "WORKSTREAM_NAMESPACE_MAPPING_INVALID", "The workstream namespace mapping is unavailable.")
        val namespace = workstream.namespaceId?.trim()?.takeIf { it.isNotEmpty() }
        if (workstream.status != WorkstreamStatus.ACTIVE || namespace == null || !NAMESPACE_ID.matches(namespace)) {
            factoryError(403, "WORKSTREAM_NAMESPACE_MAPPING_INVALID", "The workstream namespace mapping is unavailable.")
        }
        return namespace
    }

    /**
     * The namespace declared by [workstreamId] in the registry (defaulting to
     * the caller's workstream), or `null` when the workstream declares none.
     */
    fun declaredNamespaceId(scope: TenantScope, workstreamId: String = scope.workstreamId): String? =
        findDomain(scope, workstreamId)?.namespaceId?.takeIf { it.isNotBlank() }

    private fun normalizeNamespace(namespaceId: String?): String? =
        namespaceId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

    private fun normalizeOptionalNamespace(namespaceId: String?): String? =
        namespaceId?.let { io.whozoss.factory.workstream.domain.WorkstreamNamespace.require(it) }

    private fun requireNamespace(namespaceId: String?): String =
        io.whozoss.factory.workstream.domain.WorkstreamNamespace.require(namespaceId)

    private fun assertNamespaceAvailable(organizationId: String, namespaceId: String?, excludingWorkstreamId: String? = null) {
        if (namespaceId == null) return
        val existing = findByNamespace(organizationId, namespaceId)
        if (existing != null && existing.workstreamId != excludingWorkstreamId) throw namespaceConflict(namespaceId)
    }

    private fun namespaceConflict(namespaceId: String): ConflictException =
        ConflictException(
            "Namespace '$namespaceId' is already assigned to another workstream",
            mapOf("code" to "WORKSTREAM_NAMESPACE_CONFLICT", "namespaceId" to namespaceId),
        )

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

    companion object {
        private val NAMESPACE_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    }
}

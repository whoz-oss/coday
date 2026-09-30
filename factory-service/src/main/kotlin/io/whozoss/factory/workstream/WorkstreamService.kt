package io.whozoss.factory.workstream

import io.whozoss.factory.error.BadRequestException
import io.whozoss.factory.error.ConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workstream.persistence.Neo4jWorkstreamRepository
import org.springframework.stereotype.Service

/**
 * Business logic of the `/api/factory/workstreams` surface.
 *
 * The Kotlin control plane reads and writes workstream nodes through
 * [Neo4jWorkstreamRepository], always constrained by the caller's [TenantScope].
 */
@Service
class WorkstreamService(
    private val repository: Neo4jWorkstreamRepository,
) {

    private val slugPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun list(scope: TenantScope): List<Map<String, Any?>> = repository.list(scope)

    fun create(scope: TenantScope, slug: String?, name: String?, status: String?): Map<String, Any?> {
        if (slug.isNullOrBlank() || name.isNullOrBlank() || status.isNullOrBlank()) {
            throw BadRequestException("slug, name et status sont requis", mapOf("code" to "INVALID_WORKSTREAM_REQUEST"))
        }
        if (!slugPattern.matches(slug)) {
            throw BadRequestException(
                "slug invalide : lettres minuscules, chiffres et tirets uniquement (ex: talent-portal)",
                mapOf("code" to "INVALID_WORKSTREAM_SLUG"),
            )
        }
        if (repository.findById(scope, slug) != null) {
            throw ConflictException("Le workstream '$slug' existe déjà", mapOf("code" to "WORKSTREAM_ALREADY_EXISTS"))
        }
        return repository.create(scope, slug, name, status)
    }
}

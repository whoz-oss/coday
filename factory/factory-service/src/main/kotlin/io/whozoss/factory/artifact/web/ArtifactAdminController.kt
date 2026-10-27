package io.whozoss.factory.artifact.web

import io.swagger.v3.oas.annotations.Parameter
import io.whozoss.factory.artifact.error.ArtifactAdminException
import io.whozoss.factory.artifact.service.ArtifactAdminPurgeStatus
import io.whozoss.factory.artifact.service.OrganizationArtifactAdminService
import io.whozoss.factory.persistence.OrganizationScope
import io.whozoss.factory.web.AuthenticatedUser
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP boundary of factory-artifact governance.
 *
 * Three commands — GC, purge and legal hold — are exposed under
 * `/api/factory/admin/artifacts/…`. Any authenticated user can run them, in the
 * tenant of that user.
 *
 * Responses use the canonical `{ "data": ... }` envelope; errors flow through
 * the shared error envelope (`{ "error": { code, message, details } }`).
 */
@RestController
@RequestMapping("/api/factory/admin/artifacts")
class ArtifactAdminController(
    private val adminService: OrganizationArtifactAdminService,
) {

    /** Reclaims orphaned staging uploads and audits blobs against metadata rows. */
    @PostMapping("/gc", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun collectAndAuditGarbage(@Parameter(hidden = true) user: AuthenticatedUser): Map<String, Any?> =
        mapOf("data" to adminService.collectAndAuditGarbage(OrganizationScope(user.organizationId)))

    /** Purges an expired-retention artifact without an active legal hold. */
    @PostMapping("/{artifactId}/purge", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun purgeArtifact(
        @PathVariable artifactId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): Map<String, Any?> {
        val reason = (body?.get("reason") as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_PURGE_REASON
        val result = adminService.purgeArtifactAdmin(OrganizationScope(user.organizationId), artifactId, reason)
        if (!result.success) {
            val statusCode = if (result.status == ArtifactAdminPurgeStatus.NOT_FOUND) 404 else 409
            throw ArtifactAdminException(
                errorCode = result.status,
                statusCode = statusCode,
                message = "Admin purge refused: ${result.status}",
            )
        }
        return mapOf("data" to result)
    }

    /** Places or releases the legal hold of an artifact. */
    @PostMapping("/{artifactId}/legal-hold", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun setLegalHold(
        @PathVariable artifactId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) user: AuthenticatedUser,
    ): Map<String, Any?> {
        val legalHold = body?.get("legalHold")
        if (legalHold !is Boolean) {
            throw ArtifactAdminException(
                errorCode = "INVALID_LEGAL_HOLD",
                statusCode = 400,
                message = "`legalHold` must be a boolean",
            )
        }
        val reason = (body["reason"] as? String)?.takeIf { it.isNotBlank() }
        val metadata = adminService.setLegalHoldAdmin(OrganizationScope(user.organizationId), artifactId, legalHold, reason)
        return mapOf("data" to metadata)
    }

    companion object {
        const val DEFAULT_PURGE_REASON = "admin-purge"
    }
}

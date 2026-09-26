package io.whozoss.factory.artifact.web

import io.whozoss.factory.artifact.error.ArtifactAdminException
import io.whozoss.factory.artifact.service.ArtifactAdminService
import io.whozoss.factory.artifact.service.ArtifactAdminPurgeStatus
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.AdminGuard
import io.whozoss.factory.web.TrustContext
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Explicit admin HTTP boundary for factory-artifact governance.
 *
 * Faithful port of `factory/dashboard/artifact-admin-routes.mjs`. Three commands
 * — GC, purge and legal hold — are exposed under
 * `/api/factory/admin/artifacts/…`. Every one of them funnels through the same
 * explicit authorization point, [AdminGuard.requireAdminRole], before any use
 * case runs: a non-admin caller gets a `403 FORBIDDEN_ADMIN_REQUIRED` and the
 * store is never touched.
 *
 * Responses use the canonical `{ "data": ... }` envelope; errors flow through
 * the shared error envelope (`{ "error": { code, message, details } }`).
 */
@RestController
@RequestMapping("/api/factory/admin/artifacts")
class ArtifactAdminController(
    private val adminService: ArtifactAdminService,
    private val adminGuard: AdminGuard,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    /** Reclaims orphaned staging uploads and audits blobs against metadata rows. */
    @PostMapping("/gc", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun collectAndAuditGarbage(@Parameter(hidden = true) trustContext: TrustContext): Map<String, Any?> {
        adminGuard.requireAdminRole(trustContext)
        val report = adminService.collectAndAuditGarbage(scopeOf(trustContext))
        return mapOf("data" to report)
    }

    /** Purges an expired-retention artifact without an active legal hold. */
    @PostMapping("/{artifactId}/purge", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun purgeArtifact(
        @PathVariable artifactId: String,
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext,
    ): Map<String, Any?> {
        adminGuard.requireAdminRole(trustContext)
        val reason = (body?.get("reason") as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_PURGE_REASON
        val result = adminService.purgeArtifactAdmin(scopeOf(trustContext), artifactId, reason)
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
        @Parameter(hidden = true) trustContext: TrustContext,
    ): Map<String, Any?> {
        adminGuard.requireAdminRole(trustContext)
        val legalHold = body?.get("legalHold")
        if (legalHold !is Boolean) {
            throw ArtifactAdminException(
                errorCode = "INVALID_LEGAL_HOLD",
                statusCode = 400,
                message = "`legalHold` must be a boolean",
            )
        }
        val reason = (body["reason"] as? String)?.takeIf { it.isNotBlank() }
        val metadata = adminService.setLegalHoldAdmin(scopeOf(trustContext), artifactId, legalHold, reason)
        return mapOf("data" to metadata)
    }

    private fun scopeOf(trustContext: TrustContext): TenantScope =
        tenantScopeProvider.scopeOf(trustContext) ?: tenantScopeProvider.defaultScope()

    companion object {
        const val DEFAULT_PURGE_REASON = "admin-purge"
    }
}

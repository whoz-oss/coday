package io.whozoss.factory.artifact.service

import io.whozoss.factory.artifact.domain.ArtifactMetadata
import io.whozoss.factory.artifact.port.ArtifactStore
import io.whozoss.factory.artifact.port.OpenArtifactResult
import io.whozoss.factory.artifact.port.PutArtifactParams
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext

/**
 * General artifact application service.
 *
 * Thin, tenant-aware facade over the [ArtifactStore] port: it resolves the
 * [io.whozoss.factory.persistence.TenantScope] from the verified trust context
 * so callers never pass tenant identity by hand. Admin governance lives in
 * [ArtifactAdminService].
 */
class ArtifactService(
    private val store: ArtifactStore,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    /** Stores a payload in the caller's tenant scope and returns its metadata. */
    fun putArtifact(trustContext: TrustContext, params: PutArtifactParams): ArtifactMetadata =
        store.putArtifact(scopeOf(trustContext), params)

    /** Returns the metadata of an artifact, or `null` when unknown. */
    fun getArtifactMetadata(trustContext: TrustContext, artifactId: String): ArtifactMetadata? =
        store.getArtifactMetadata(scopeOf(trustContext), artifactId)

    /** Opens the payload of an available artifact, or `null` when unavailable. */
    fun openArtifact(trustContext: TrustContext, artifactId: String): OpenArtifactResult? =
        store.openArtifact(scopeOf(trustContext), artifactId)

    private fun scopeOf(trustContext: TrustContext) =
        tenantScopeProvider.scopeOf(trustContext) ?: tenantScopeProvider.defaultScope()
}

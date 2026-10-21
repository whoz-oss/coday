package io.whozoss.factory.artifact.service

import io.whozoss.factory.artifact.domain.ArtifactMetadata
import io.whozoss.factory.artifact.port.ArtifactStore
import io.whozoss.factory.artifact.port.OpenArtifactResult
import io.whozoss.factory.artifact.port.PutArtifactParams
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider

/**
 * General artifact application service.
 *
 * Thin, tenant-aware facade over the [ArtifactStore] port. Each operation runs in
 * the given [TenantScope]; when none is supplied the configured default tenant is
 * used. Admin governance lives in [ArtifactAdminService].
 */
class ArtifactService(
    private val store: ArtifactStore,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    /** Stores a payload in the given tenant scope and returns its metadata. */
    fun putArtifact(params: PutArtifactParams, scope: TenantScope? = null): ArtifactMetadata =
        store.putArtifact(scope ?: tenantScopeProvider.defaultScope(), params)

    /** Returns the metadata of an artifact, or `null` when unknown. */
    fun getArtifactMetadata(artifactId: String, scope: TenantScope? = null): ArtifactMetadata? =
        store.getArtifactMetadata(scope ?: tenantScopeProvider.defaultScope(), artifactId)

    /** Opens the payload of an available artifact, or `null` when unavailable. */
    fun openArtifact(artifactId: String, scope: TenantScope? = null): OpenArtifactResult? =
        store.openArtifact(scope ?: tenantScopeProvider.defaultScope(), artifactId)
}

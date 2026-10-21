package io.whozoss.factory.artifact.service

import io.whozoss.factory.artifact.domain.ArtifactMetadata
import io.whozoss.factory.artifact.port.ArtifactStore
import io.whozoss.factory.artifact.port.OpenArtifactResult
import io.whozoss.factory.artifact.port.PutArtifactParams
import io.whozoss.factory.persistence.TenantScope

/** Artifact operations always require an explicitly resolved business scope. */
class ArtifactService(private val store: ArtifactStore) {
    fun putArtifact(params: PutArtifactParams, scope: TenantScope): ArtifactMetadata = store.putArtifact(scope, params)
    fun getArtifactMetadata(artifactId: String, scope: TenantScope): ArtifactMetadata? = store.getArtifactMetadata(scope, artifactId)
    fun openArtifact(artifactId: String, scope: TenantScope): OpenArtifactResult? = store.openArtifact(scope, artifactId)
}

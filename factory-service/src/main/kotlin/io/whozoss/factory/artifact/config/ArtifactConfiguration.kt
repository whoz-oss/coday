package io.whozoss.factory.artifact.config

import io.whozoss.factory.artifact.infrastructure.blob.ArtifactBlobClient
import io.whozoss.factory.artifact.infrastructure.blob.InMemoryArtifactBlobClient
import io.whozoss.factory.artifact.infrastructure.blob.S3ArtifactBlobClient
import io.whozoss.factory.artifact.infrastructure.persistence.Neo4jArtifactStore
import io.whozoss.factory.artifact.infrastructure.persistence.SpringDataNeo4jArtifactRepository
import io.whozoss.factory.artifact.port.ArtifactGcMetadataLister
import io.whozoss.factory.artifact.service.ArtifactAdminService
import io.whozoss.factory.artifact.service.ArtifactService
import io.whozoss.factory.persistence.TenantScopeProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Composition root of the ARTEFACTS aggregate.
 *
 * Wires the blob backend selected by `factory.artifact.blob-client`
 * (`in-memory` by default, `s3` for S3 / MinIO), the Neo4j-backed
 * [Neo4jArtifactStore], the admin governance use cases and the general
 * artifact application service. It is strictly additive: it never touches the
 * shared Factory socle.
 */
@Configuration
class ArtifactConfiguration {

    @Bean
    fun artifactBlobClient(properties: ArtifactProperties): ArtifactBlobClient =
        if (properties.blobClient.equals("s3", ignoreCase = true)) {
            S3ArtifactBlobClient(
                endpoint = properties.s3.endpoint,
                region = properties.s3.region,
                bucket = properties.s3.bucket,
                accessKeyId = properties.s3.accessKeyId,
                secretAccessKey = properties.s3.secretAccessKey,
                sessionToken = properties.s3.sessionToken,
                defaultSignedUrlTtlSeconds = properties.signedUrlTtlSeconds,
            )
        } else {
            InMemoryArtifactBlobClient(
                endpoint = properties.s3.endpoint,
                bucket = properties.s3.bucket,
                defaultSignedUrlTtlSeconds = properties.signedUrlTtlSeconds,
            )
        }

    @Bean
    fun artifactStore(
        repository: SpringDataNeo4jArtifactRepository,
        blobClient: ArtifactBlobClient,
        properties: ArtifactProperties,
    ): Neo4jArtifactStore =
        Neo4jArtifactStore(
            repository = repository,
            blobClient = blobClient,
            defaultRetentionDays = properties.retentionDays,
            uploadPrefix = properties.uploadPrefix,
            objectPrefix = properties.objectPrefix,
        )

    @Bean
    fun artifactAdminService(
        artifactStore: Neo4jArtifactStore,
        blobClient: ArtifactBlobClient,
        properties: ArtifactProperties,
    ): ArtifactAdminService {
        val lister: ArtifactGcMetadataLister = artifactStore
        return ArtifactAdminService(
            store = artifactStore,
            metadataLister = lister,
            blobClient = blobClient,
            uploadPrefix = properties.uploadPrefix,
            objectPrefix = properties.objectPrefix,
        )
    }

    @Bean
    fun artifactService(
        artifactStore: Neo4jArtifactStore,
        tenantScopeProvider: TenantScopeProvider,
    ): ArtifactService = ArtifactService(store = artifactStore, tenantScopeProvider = tenantScopeProvider)
}

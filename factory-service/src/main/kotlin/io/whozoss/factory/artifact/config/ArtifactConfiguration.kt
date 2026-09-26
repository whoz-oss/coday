package io.whozoss.factory.artifact.config

import io.whozoss.factory.artifact.infrastructure.blob.ArtifactBlobClient
import io.whozoss.factory.artifact.infrastructure.blob.InMemoryArtifactBlobClient
import io.whozoss.factory.artifact.infrastructure.blob.S3ArtifactBlobClient
import io.whozoss.factory.artifact.infrastructure.persistence.PostgresArtifactStore
import io.whozoss.factory.artifact.port.ArtifactGcMetadataLister
import io.whozoss.factory.artifact.service.ArtifactAdminService
import io.whozoss.factory.artifact.service.ArtifactService
import io.whozoss.factory.persistence.TenantScopeProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Composition root of the ARTEFACTS aggregate.
 *
 * Wires the blob backend selected by `factory.artifact.blob-client`
 * (`in-memory` by default, `s3` for S3 / MinIO), the PostgreSQL-backed
 * [PostgresArtifactStore], the admin governance use cases and the general
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
        jdbcTemplate: JdbcTemplate,
        blobClient: ArtifactBlobClient,
        properties: ArtifactProperties,
    ): PostgresArtifactStore =
        PostgresArtifactStore(
            jdbcTemplate = jdbcTemplate,
            blobClient = blobClient,
            defaultRetentionDays = properties.retentionDays,
            uploadPrefix = properties.uploadPrefix,
            objectPrefix = properties.objectPrefix,
        )

    @Bean
    fun artifactAdminService(
        artifactStore: PostgresArtifactStore,
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
        artifactStore: PostgresArtifactStore,
        tenantScopeProvider: TenantScopeProvider,
    ): ArtifactService = ArtifactService(store = artifactStore, tenantScopeProvider = tenantScopeProvider)
}

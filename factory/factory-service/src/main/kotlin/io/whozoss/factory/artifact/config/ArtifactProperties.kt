package io.whozoss.factory.artifact.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.artifact.*` configuration tree.
 *
 * Mirrors the environment variables the Node Factory reads:
 *   - `ARTIFACT_RETENTION_DAYS` (default 90) — default retention window;
 *   - `ARTIFACT_SIGNED_URL_TTL` (default 900) — presigned URL time-to-live;
 *   - `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`,
 *     `S3_SECRET_ACCESS_KEY` — S3 / MinIO connection settings.
 *
 * The environment fallbacks themselves are declared in `application.yml`.
 */
@ConfigurationProperties(prefix = "factory.artifact")
data class ArtifactProperties(
    /** Default retention window, in days, when none is requested. */
    val retentionDays: Int = 90,
    /** Default presigned URL time-to-live, in seconds. */
    val signedUrlTtlSeconds: Long = 900,
    /**
     * Active blob-storage backend: `in-memory` (default, offline) or `s3`.
     */
    val blobClient: String = "in-memory",
    /** Prefix of transient staging uploads. */
    val uploadPrefix: String = "uploads",
    /** Prefix of content-addressed payload blobs. */
    val objectPrefix: String = "objects",
    val s3: S3 = S3(),
) {
    data class S3(
        val endpoint: String = "http://localhost:9000",
        val region: String = "us-east-1",
        val bucket: String = "coday-artifacts",
        val accessKeyId: String = "minioadmin",
        val secretAccessKey: String = "minioadmin",
        val sessionToken: String? = null,
    )
}

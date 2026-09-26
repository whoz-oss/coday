package io.whozoss.factory.artifact.infrastructure.blob

import java.io.InputStream
import java.time.Instant

/**
 * Structural object-storage contract used by the artifact store.
 *
 * Ported from `ArtifactBlobClient` in
 * `factory/src/adapters/artifact/postgres-artifact-store.ts`. Concrete
 * implementations target an in-memory map (local/testing) or S3 / MinIO.
 */
interface ArtifactBlobClient {

    /** Stores an object at [key]. */
    fun putObject(key: String, body: ByteArray, contentType: String? = null)

    /** Server-side copies [sourceKey] to [destinationKey]. */
    fun copyObject(sourceKey: String, destinationKey: String)

    /** Reads an object, or returns `null` when it does not exist. */
    fun getObject(key: String): InputStream?

    /** Deletes an object. Returns `true` when a deletion happened. */
    fun deleteObject(key: String): Boolean

    /** Returns whether an object exists. */
    fun headObject(key: String): Boolean

    /** Lists every object key under [prefix]. */
    fun listObjectKeys(prefix: String): List<String>

    /**
     * Computes a pre-signed, temporarily readable URL for [key], or returns
     * `null` when the implementation cannot presign.
     */
    fun getSignedUrl(key: String, expiresInSeconds: Long? = null, now: Instant = Instant.now()): String?
}

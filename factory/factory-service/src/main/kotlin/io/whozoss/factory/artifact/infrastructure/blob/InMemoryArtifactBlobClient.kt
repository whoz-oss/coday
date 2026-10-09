package io.whozoss.factory.artifact.infrastructure.blob

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ArtifactBlobClient] for local runs and tests.
 *
 * Thread-safe: payload bytes and content types live in concurrent maps. Pre-signed
 * URLs are mocked locally (no network round-trip) so the upload-then-commit
 * protocol and governance rules can be exercised offline.
 */
class InMemoryArtifactBlobClient(
    private val endpoint: String = "http://localhost:9000",
    private val bucket: String = "mock-bucket",
    private val defaultSignedUrlTtlSeconds: Long = DEFAULT_SIGNED_URL_TTL_SECONDS,
) : ArtifactBlobClient {

    private val objects = ConcurrentHashMap<String, ByteArray>()
    private val contentTypes = ConcurrentHashMap<String, String>()

    override fun putObject(key: String, body: ByteArray, contentType: String?) {
        objects[key] = body.copyOf()
        if (contentType != null) {
            contentTypes[key] = contentType
        } else {
            contentTypes.remove(key)
        }
    }

    override fun copyObject(sourceKey: String, destinationKey: String) {
        val source = objects[sourceKey] ?: error("missing source $sourceKey")
        objects[destinationKey] = source.copyOf()
        contentTypes[sourceKey]?.let { contentTypes[destinationKey] = it }
    }

    override fun getObject(key: String): InputStream? {
        val body = objects[key] ?: return null
        return ByteArrayInputStream(body.copyOf())
    }

    override fun deleteObject(key: String): Boolean {
        contentTypes.remove(key)
        return objects.remove(key) != null
    }

    override fun headObject(key: String): Boolean = objects.containsKey(key)

    override fun listObjectKeys(prefix: String): List<String> = objects.keys.filter { it.startsWith(prefix) }

    override fun getSignedUrl(key: String, expiresInSeconds: Long?, now: Instant): String {
        val ttl = expiresInSeconds?.takeIf { it > 0 } ?: defaultSignedUrlTtlSeconds
        val base = endpoint.trimEnd('/')
        return "$base/$bucket/$key?X-Amz-Expires=$ttl&X-Amz-Signature=in-memory"
    }

    /** Test/introspection helper: the content type stored for [key], if any. */
    fun contentTypeOf(key: String): String? = contentTypes[key]

    companion object {
        const val DEFAULT_SIGNED_URL_TTL_SECONDS = 900L
    }
}

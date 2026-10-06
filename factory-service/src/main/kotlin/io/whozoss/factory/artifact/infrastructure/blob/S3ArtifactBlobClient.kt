package io.whozoss.factory.artifact.infrastructure.blob

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS S3 / MinIO [ArtifactBlobClient] with AWS Signature Version 4.
 *
 * Faithful port of `factory/src/adapters/artifact/s3-object-client.ts`. The
 * client speaks the S3 REST API directly over [HttpClient] and signs every
 * request from `javax.crypto` — no AWS SDK dependency — so the low-level
 * operations the artifact store needs are `PUT` (with optional server-side
 * copy), `GET`, `HEAD`, `DELETE`, `ListObjectsV2` and SigV4 query presigning.
 */
class S3ArtifactBlobClient(
    private val endpoint: String,
    private val region: String,
    private val bucket: String,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val sessionToken: String? = null,
    private val defaultSignedUrlTtlSeconds: Long = DEFAULT_SIGNED_URL_TTL_SECONDS,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : ArtifactBlobClient {

    private val base: String = endpoint.trimEnd('/')

    private val host: String = run {
        val uri = URI.create(base)
        val defaultPort = when (uri.scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        val port = uri.port
        if (port == -1 || port == defaultPort) uri.host else "${uri.host}:$port"
    }

    override fun putObject(key: String, body: ByteArray, contentType: String?) {
        send("PUT", key, null, body, contentType, null, HttpResponse.BodyHandlers.discarding())
    }

    override fun copyObject(sourceKey: String, destinationKey: String) {
        val copySource = "/$bucket/${encodeS3KeyPath(sourceKey)}"
        send("PUT", destinationKey, null, ByteArray(0), null, copySource, HttpResponse.BodyHandlers.discarding())
    }

    override fun getObject(key: String): InputStream? {
        val response = send("GET", key, null, null, null, null, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() == 404) {
            runCatching { response.body()?.close() }
            return null
        }
        return response.body()
    }

    override fun deleteObject(key: String): Boolean {
        val response = send("DELETE", key, null, ByteArray(0), null, null, HttpResponse.BodyHandlers.discarding())
        return response.statusCode() != 404
    }

    override fun headObject(key: String): Boolean {
        val response = send("HEAD", key, null, null, null, null, HttpResponse.BodyHandlers.discarding())
        return response.statusCode() != 404
    }

    override fun listObjectKeys(prefix: String): List<String> {
        val keys = mutableListOf<String>()
        var continuationToken: String? = null
        while (true) {
            val query = linkedMapOf("list-type" to "2", "prefix" to prefix)
            continuationToken?.let { query["continuation-token"] = it }
            val response = send("GET", "", query, null, null, null, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 404) break
            val xml = response.body() ?: ""
            KEY_PATTERN.findAll(xml).forEach { match -> keys.add(decodeXmlEntities(match.groupValues[1])) }
            if (!xml.contains("<IsTruncated>true</IsTruncated>")) break
            val token = NEXT_TOKEN_PATTERN.find(xml)?.groupValues?.get(1) ?: break
            continuationToken = decodeXmlEntities(token)
        }
        return keys
    }

    /**
     * Computes a SigV4 pre-signed GET URL for [key] using query-parameter
     * authentication (no network round-trip). The TTL is resolved from
     * [expiresInSeconds], then the configured default.
     */
    override fun getSignedUrl(key: String, expiresInSeconds: Long?, now: Instant): String {
        val ttl = expiresInSeconds?.takeIf { it > 0 } ?: defaultSignedUrlTtlSeconds
        val amzDate = formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)
        val canonicalUri = canonicalUri(key)
        val scope = "$dateStamp/$region/$SERVICE/aws4_request"

        val query = linkedMapOf(
            "X-Amz-Algorithm" to SIGNING_ALGORITHM,
            "X-Amz-Credential" to "$accessKeyId/$scope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to ttl.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        sessionToken?.let { query["X-Amz-Security-Token"] = it }

        val canonicalQuery = canonicalQuery(query)
        val canonicalHeaders = "host:$host\n"
        val canonicalRequest = listOf(
            "GET",
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            "host",
            UNSIGNED_PAYLOAD,
        ).joinToString("\n")

        val stringToSign = listOf(SIGNING_ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest)).joinToString("\n")
        val signature = hex(hmac(signingKey(dateStamp), stringToSign))

        return "$base$canonicalUri?$canonicalQuery&X-Amz-Signature=$signature"
    }

    private fun <T> send(
        method: String,
        key: String,
        query: Map<String, String>?,
        body: ByteArray?,
        contentType: String?,
        copySource: String?,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val canonicalQuery = canonicalQuery(query)
        val canonicalUri = canonicalUri(key)
        val url = "$base$canonicalUri${if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery"}"
        val payload = body ?: ByteArray(0)
        val payloadHash = sha256Hex(payload)

        val amzDate = formatAmzDate(Instant.now())
        val dateStamp = amzDate.substring(0, 8)

        val signingHeaders = linkedMapOf(
            "host" to host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        )
        sessionToken?.let { signingHeaders["x-amz-security-token"] = it }
        copySource?.let { signingHeaders["x-amz-copy-source"] = it }

        val signedHeaderNames = signingHeaders.keys
            .filter { it == "host" || it.startsWith("x-amz-") }
            .sorted()
        val canonicalHeaders = signedHeaderNames.joinToString("") { name ->
            "$name:${signingHeaders.getValue(name).trim()}\n"
        }
        val signedHeaders = signedHeaderNames.joinToString(";")
        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val scope = "$dateStamp/$region/$SERVICE/aws4_request"
        val stringToSign = listOf(SIGNING_ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest)).joinToString("\n")
        val signature = hex(hmac(signingKey(dateStamp), stringToSign))

        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("authorization", "$SIGNING_ALGORITHM Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
        sessionToken?.let { builder.header("x-amz-security-token", it) }
        contentType?.let { builder.header("content-type", it) }
        copySource?.let { builder.header("x-amz-copy-source", it) }
        builder.method(method, HttpRequest.BodyPublishers.ofByteArray(payload))

        val response = httpClient.send(builder.build(), handler)
        if (response.statusCode() >= 400 && !(method in setOf("GET", "HEAD", "DELETE") && response.statusCode() == 404)) {
            throw IllegalStateException("S3 $method ${key.ifEmpty { "<bucket>" }} failed with status ${response.statusCode()}")
        }
        return response
    }

    private fun signingKey(dateStamp: String): ByteArray {
        val kDate = hmac("AWS4$secretAccessKey".toByteArray(StandardCharsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, SERVICE)
        return hmac(kService, "aws4_request")
    }

    private fun canonicalUri(key: String): String =
        "/${encodeS3Component(bucket)}${if (key.isEmpty()) "" else "/${encodeS3KeyPath(key)}"}"

    private fun canonicalQuery(query: Map<String, String>?): String {
        if (query.isNullOrEmpty()) return ""
        return query.keys.sorted().joinToString("&") { name ->
            "${encodeS3Component(name)}=${encodeS3Component(query.getValue(name))}"
        }
    }

    private fun encodeS3KeyPath(key: String): String = key.split("/").joinToString("/") { encodeS3Component(it) }

    private fun encodeS3Component(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val char = byte.toInt().toChar()
            val unreserved = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '-' || char == '_' || char == '.' || char == '~'
            if (unreserved) {
                out.append(char)
            } else {
                out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    private fun formatAmzDate(instant: Instant): String = AMZ_DATE_FORMAT.format(instant)

    private fun sha256Hex(data: String): String = sha256Hex(data.toByteArray(StandardCharsets.UTF_8))

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        return hex(digest)
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun decodeXmlEntities(value: String): String =
        value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    companion object {
        const val DEFAULT_SIGNED_URL_TTL_SECONDS = 900L

        private const val SIGNING_ALGORITHM = "AWS4-HMAC-SHA256"
        private const val SERVICE = "s3"
        private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

        private val AMZ_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private val KEY_PATTERN = Regex("<Key>(.*?)</Key>", RegexOption.DOT_MATCHES_ALL)
        private val NEXT_TOKEN_PATTERN =
            Regex("<NextContinuationToken>(.*?)</NextContinuationToken>", RegexOption.DOT_MATCHES_ALL)
    }
}

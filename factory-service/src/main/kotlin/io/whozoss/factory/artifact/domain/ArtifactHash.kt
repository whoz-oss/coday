package io.whozoss.factory.artifact.domain

import java.security.MessageDigest
import java.util.UUID

/**
 * Content addressing for artifacts. Ported from
 * `factory/src/adapters/artifact/artifact-hash.ts`.
 */
object ArtifactHash {

    /** Content address prefix used by every artifact hash. */
    const val PREFIX = "sha256"

    /** Computes the content address (`sha256:<hex>`) of a payload. */
    fun compute(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return "$PREFIX:${digest.toHex()}"
    }

    /** Generates a fresh, opaque artifact identifier. */
    fun createArtifactId(): String = UUID.randomUUID().toString()

    /** Extracts the bare hex digest from a `sha256:<hex>` content address. */
    fun digest(hash: String): String =
        if (hash.startsWith("$PREFIX:")) hash.substring(PREFIX.length + 1) else hash

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            out.append(HEX[(byte.toInt() shr 4) and 0x0F])
            out.append(HEX[byte.toInt() and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

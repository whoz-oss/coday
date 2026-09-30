package io.whozoss.agentos.plugins.http.file

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Identity of a document file's content, part of the catalogue cache key: a changed file is a new key. */
data class FileStamp(val lastModifiedMillis: Long, val size: Long)

/** Outcome of [SpecFileSource.read]. */
sealed interface FileReadOutcome {
    data class Read(val text: String) : FileReadOutcome

    /**
     * @property reason Safe to log and to show to an administrator; never the content of the file nor its path
     *   (the config already names it).
     */
    data class Failed(val reason: String) : FileReadOutcome
}

/** Where `spec.file` documents come from; the seam the catalogue cache is tested through. */
interface SpecFileSource {
    /** The current stamp of the file, or null when its attributes cannot be read (missing file, invalid path). */
    fun stamp(path: String): FileStamp?

    fun read(path: String, maxBytes: Long): FileReadOutcome
}

/**
 * Reads an OpenAPI document from the local filesystem: the path must name a regular readable file of at
 * most `maxBytes`, decoded as UTF-8. The size is checked before reading, and the read itself stops one byte
 * past the limit, so a file that grows meanwhile never lands in memory beyond `maxBytes`.
 *
 * The extension allowlist is enforced by the config parser; no other path restriction is applied: the
 * process reads whatever file the config names, which is the trust boundary of the config directory.
 */
class SpecFileReader : SpecFileSource {

    override fun stamp(path: String): FileStamp? =
        try {
            stampOf(Path.of(path))
        } catch (e: InvalidPathException) {
            null
        } catch (e: IOException) {
            null
        }

    override fun read(path: String, maxBytes: Long): FileReadOutcome {
        val file = try {
            Path.of(path)
        } catch (e: InvalidPathException) {
            return FileReadOutcome.Failed("document file path is invalid: ${e.reason}")
        }
        if (!Files.exists(file)) return FileReadOutcome.Failed("document file does not exist")
        if (!Files.isRegularFile(file)) return FileReadOutcome.Failed("document file is not a regular file")
        if (!Files.isReadable(file)) return FileReadOutcome.Failed("document file is not readable")
        return try {
            val size = Files.size(file)
            if (size > maxBytes) return FileReadOutcome.Failed(tooLarge(size, maxBytes))
            val bytes = Files.newInputStream(file).use { it.readNBytes(boundedLength(maxBytes)) }
            if (bytes.size > maxBytes) return FileReadOutcome.Failed(tooLarge(Files.size(file), maxBytes))
            FileReadOutcome.Read(text = decodeUtf8(bytes))
        } catch (e: IOException) {
            FileReadOutcome.Failed("document file cannot be read (${e::class.simpleName})")
        }
    }

    /** One byte more than allowed, so that a file grown past the limit since the size check is detected. */
    private fun boundedLength(maxBytes: Long): Int =
        if (maxBytes >= Int.MAX_VALUE) Int.MAX_VALUE else (maxBytes + 1).toInt()

    /** Strict decoding: a malformed sequence is a [java.nio.charset.MalformedInputException], not a `?`. */
    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun tooLarge(size: Long, maxBytes: Long): String =
        "document file is $size bytes, larger than the allowed $maxBytes bytes"

    private fun stampOf(file: Path): FileStamp {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        return FileStamp(lastModifiedMillis = attributes.lastModifiedTime().toMillis(), size = attributes.size())
    }
}

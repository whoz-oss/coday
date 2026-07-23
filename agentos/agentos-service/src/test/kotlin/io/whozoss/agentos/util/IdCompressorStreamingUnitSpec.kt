package io.whozoss.agentos.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [IdCompressorService] streaming decompression via [IdCompressorService.feed]
 * and [IdCompressorService.flush].
 *
 * Each test creates its own [IdCompressorService] and [MessageCompressorBuffer] to remain
 * fully independent. Compressed token aliases are hardcoded based on the known offset
 * assigned by [IdCompressorService.compress] for position-0 IDs, so tests do not rely
 * on a shared compressor state.
 *
 * ## Alias derivation
 * `IdCompressorService` assigns aliases as `<PREFIX><base36(absoluteOffset)>` where the
 * offset is the character position of the ID in the first [compress] call. For a call
 * with the raw ID at position 0 the alias is `UI0` (UUID) or `OI0` (ObjectId).
 * Tests that feed longer strings predict the offset from the ID's start position.
 */
class IdCompressorStreamingUnitSpec : StringSpec({

    val uuid      = "550e8400-e29b-41d4-a716-446655440000"
    val uuid2     = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    val objectId  = "507f1f77bcf86cd799439011"

    // -------------------------------------------------------------------------
    // Helper: run all chunks through feed/flush and return the full output.
    // -------------------------------------------------------------------------

    fun processAll(service: IdCompressorService, buffer: MessageCompressorBuffer, vararg chunks: String): String {
        val sb = StringBuilder()
        for (chunk in chunks) sb.append(service.feed(chunk, buffer))
        sb.append(service.flush(buffer))
        return sb.toString()
    }

    // Derive the alias that compress() will assign when the ID starts at position `offset`.
    fun uuidAlias(offset: Int)     = "${IdCompressorService.UUID_COMPRESSED_VALUE_PREFIX}${offset.toString(36)}"
    fun objectIdAlias(offset: Int) = "${IdCompressorService.OBJECTID_COMPRESSED_VALUE_PREFIX}${offset.toString(36)}"

    // -------------------------------------------------------------------------
    // Passthrough: no IDs compressed — text flows through unchanged
    // -------------------------------------------------------------------------

    "passthrough when no IDs were compressed" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        processAll(service, buffer, "hello ", "world") shouldBe "hello world"
    }

    "plain text passes through unchanged even after IDs were compressed" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress("context: $uuid", buffer)
        processAll(service, buffer, "no ids here") shouldBe "no ids here"
    }

    // -------------------------------------------------------------------------
    // Single chunk: complete token in one piece — hardcoded aliases
    // -------------------------------------------------------------------------

    "single chunk containing complete UUID token is decompressed" {
        // UUID at position 0 → alias UI0
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        val alias = uuidAlias(0)
        processAll(service, buffer, "Profile $alias found.") shouldBe "Profile $uuid found."
    }

    "single chunk containing complete ObjectId token is decompressed" {
        // ObjectId at position 0 → alias OI0
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(objectId, buffer)
        val alias = objectIdAlias(0)
        processAll(service, buffer, "Entity $alias created.") shouldBe "Entity $objectId created."
    }

    // -------------------------------------------------------------------------
    // One-character-at-a-time: the real LLM streaming case
    // -------------------------------------------------------------------------

    "UUID token arriving one character at a time is correctly decompressed" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        val alias = uuidAlias(0)
        val chunks = alias.map { it.toString() }.toTypedArray()
        processAll(service, buffer, *chunks) shouldBe uuid
    }

    "UUID token embedded in text arriving one character at a time" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        val alias = uuidAlias(0)
        val fullText = "The id $alias was promoted."
        val chunks = fullText.map { it.toString() }.toTypedArray()
        processAll(service, buffer, *chunks) shouldBe "The id $uuid was promoted."
    }

    "ObjectId token arriving one character at a time is correctly decompressed" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(objectId, buffer)
        val alias = objectIdAlias(0)
        val chunks = alias.map { it.toString() }.toTypedArray()
        processAll(service, buffer, *chunks) shouldBe objectId
    }

    // -------------------------------------------------------------------------
    // Boundary split: token split at an arbitrary position across two chunks
    // -------------------------------------------------------------------------

    "UUID token split after the prefix (UI) across two chunks" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        val alias = uuidAlias(0)
        val part1 = alias.substring(0, 2)   // "UI"
        val part2 = alias.substring(2)       // base36 suffix
        processAll(service, buffer, part1, part2) shouldBe uuid
    }

    "UUID token split at mid-token position across two chunks" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        val alias = uuidAlias(0)
        val mid = alias.length / 2
        processAll(service, buffer, alias.substring(0, mid), alias.substring(mid)) shouldBe uuid
    }

    // -------------------------------------------------------------------------
    // Multiple IDs — each test uses independent service+buffer
    // -------------------------------------------------------------------------

    "multiple UUID tokens in the stream are all decompressed" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        // uuid at offset 0, uuid2 at offset uuid.length
        service.compress(uuid, buffer)
        service.compress(uuid2, buffer)
        val a1 = uuidAlias(0)
        val a2 = uuidAlias(uuid.length)
        processAll(service, buffer, "First: $a1, ", "second: $a2.") shouldBe
            "First: $uuid, second: $uuid2."
    }

    "UUID and ObjectId in same stream are both decompressed" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        service.compress(objectId, buffer)
        val cu = uuidAlias(0)
        val co = objectIdAlias(uuid.length)
        processAll(service, buffer, "uuid=$cu ", "oid=$co") shouldBe "uuid=$uuid oid=$objectId"
    }

    "same ID appearing multiple times is decompressed every occurrence" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        val alias = uuidAlias(0)
        processAll(service, buffer, "$alias and $alias") shouldBe "$uuid and $uuid"
    }

    // -------------------------------------------------------------------------
    // Flush behaviour
    // -------------------------------------------------------------------------

    "flush decompresses a token that remained entirely in the carry" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        val alias = uuidAlias(0)
        val fed = alias.map { it.toString() }.fold("") { acc, c -> acc + service.feed(c, buffer) }
        val flushed = service.flush(buffer)
        (fed + flushed) shouldBe uuid
    }

    "flush returns empty string when carry is empty after draining" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        service.feed("hello world, no ids here", buffer)
        service.flush(buffer) shouldBe ""
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    "empty chunks are handled without errors" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        processAll(service, buffer, "", "hello", "", " world", "") shouldBe "hello world"
    }

    // -------------------------------------------------------------------------
    // Realistic multi-chunk scenario: text + IDs interleaved across many chunks
    // -------------------------------------------------------------------------

    "realistic response with multiple IDs and surrounding text arriving in small chunks" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        service.compress(uuid2, buffer)
        service.compress(objectId, buffer)
        val cu1 = uuidAlias(0)
        val cu2 = uuidAlias(uuid.length)
        val co1 = objectIdAlias(uuid.length + uuid2.length)

        val fullResponse = "Voici les identifiants :\n- $cu1 (profil A)\n- $cu2 (profil B)\n- $co1 (entité C)"
        val expected     = "Voici les identifiants :\n- $uuid (profil A)\n- $uuid2 (profil B)\n- $objectId (entité C)"

        val chunks = fullResponse.chunked(3).toTypedArray()
        processAll(service, buffer, *chunks) shouldBe expected
    }

    "token split right at UI/OI prefix boundary across chunks" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        val alias = uuidAlias(0)
        // Construct: "Profile: UI" then digits one char at a time, then trailing text
        val prefix = "Profile: "
        val chunks = mutableListOf<String>()
        chunks.add(prefix + alias.substring(0, 2))   // "Profile: UI"
        alias.substring(2).forEach { chunks.add(it.toString()) }
        chunks.add(" is active.")
        processAll(service, buffer, *chunks.toTypedArray()) shouldBe "Profile: $uuid is active."
    }

    "two consecutive tokens with no separator arriving in mixed chunk sizes" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress(uuid, buffer)
        service.compress(uuid2, buffer)
        val c1 = uuidAlias(0)
        val c2 = uuidAlias(uuid.length)
        val fullResponse = "$c1$c2"
        val chunks = fullResponse.chunked(2).toTypedArray()
        processAll(service, buffer, *chunks) shouldBe "$uuid$uuid2"
    }

    // -------------------------------------------------------------------------
    // Data-driven: compress/decompress round-trip for various ID types
    // -------------------------------------------------------------------------

    data class RoundTripCase(val label: String, val id: String, val isObjectId: Boolean = false)

    val roundTripCases = listOf(
        RoundTripCase("UUID lowercase",  "550e8400-e29b-41d4-a716-446655440000"),
        RoundTripCase("UUID uppercase",  "550E8400-E29B-41D4-A716-446655440000"),
        RoundTripCase("UUID v4",         "6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
        RoundTripCase("ObjectId",        "507f1f77bcf86cd799439011", isObjectId = true),
        RoundTripCase("ObjectId 2",      "6790ca2213906f27c141a80b", isObjectId = true),
    )

    roundTripCases.forEach { (label, id, isObjectId) ->
        "round-trip: $label survives compress → stream → decompress" {
            val service = IdCompressorService()
            val buffer = service.newBuffer()
            service.compress(id, buffer)
            val alias = if (isObjectId) objectIdAlias(0) else uuidAlias(0)
            // Stream the alias character by character
            val chunks = alias.map { it.toString() }.toTypedArray()
            // The compressor preserves original casing — it stores and restores the ID exactly
            // as it appeared in the compressed input, with no case transformation.
            processAll(service, buffer, *chunks) shouldBe id
        }
    }
})

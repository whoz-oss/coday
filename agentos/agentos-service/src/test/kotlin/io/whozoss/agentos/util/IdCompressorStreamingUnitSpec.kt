package io.whozoss.agentos.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [IdCompressor.StreamingDecompressor].
 *
 * The decompressor must correctly restore original IDs even when a compressed token
 * arrives one character at a time across many tiny chunks (the real LLM streaming case).
 */
class IdCompressorStreamingUnitSpec : StringSpec({

    val uuid = "550e8400-e29b-41d4-a716-446655440000"
    val objectId = "507f1f77bcf86cd799439011"

    // -------------------------------------------------------------------------
    // Helper: run all chunks through the decompressor and return the full output.
    // -------------------------------------------------------------------------

    fun IdCompressor.StreamingDecompressor.processAll(vararg chunks: String): String {
        val sb = StringBuilder()
        for (chunk in chunks) sb.append(feed(chunk))
        sb.append(flush())
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Passthrough: no IDs compressed — text flows through unchanged
    // -------------------------------------------------------------------------

    "passthrough when no IDs were compressed" {
        val compressor = IdCompressor()
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll("hello ", "world") shouldBe "hello world"
    }

    "plain text passes through unchanged even after IDs were compressed" {
        val compressor = IdCompressor()
        compressor.compress("context: $uuid")
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll("no ids here") shouldBe "no ids here"
    }

    // -------------------------------------------------------------------------
    // Single chunk: complete token in one piece
    // -------------------------------------------------------------------------

    "single chunk containing complete UUID token is decompressed" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(uuid)
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll("Profile $compressed found.") shouldBe "Profile $uuid found."
    }

    "single chunk containing complete ObjectId token is decompressed" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(objectId)
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll("Entity $compressed created.") shouldBe "Entity $objectId created."
    }

    // -------------------------------------------------------------------------
    // One-character-at-a-time: the real LLM streaming case
    // -------------------------------------------------------------------------

    "UUID token arriving one character at a time is correctly decompressed" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(uuid)
        // Simulate LLM streaming every character individually
        val chunks = compressed.map { it.toString() }.toTypedArray()
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll(*chunks) shouldBe uuid
    }

    "UUID token embedded in text arriving one character at a time" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(uuid)
        val fullText = "The id $compressed was promoted."
        val chunks = fullText.map { it.toString() }.toTypedArray()
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll(*chunks) shouldBe "The id $uuid was promoted."
    }

    "ObjectId token arriving one character at a time is correctly decompressed" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(objectId)
        val chunks = compressed.map { it.toString() }.toTypedArray()
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll(*chunks) shouldBe objectId
    }

    // -------------------------------------------------------------------------
    // Boundary split: token split at an arbitrary position across two chunks
    // -------------------------------------------------------------------------

    "UUID token split after the prefix (UI) across two chunks" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(uuid)
        // Split right after the 'UI' prefix
        val part1 = compressed.substring(0, 2)   // "UI"
        val part2 = compressed.substring(2)       // "2fj" etc.
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll(part1, part2) shouldBe uuid
    }

    "UUID token split at mid-token position across two chunks" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(uuid)
        val mid = compressed.length / 2
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll(compressed.substring(0, mid), compressed.substring(mid)) shouldBe uuid
    }

    // -------------------------------------------------------------------------
    // Multiple IDs
    // -------------------------------------------------------------------------

    "multiple UUID tokens in the stream are all decompressed" {
        val uuid2 = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        val compressor = IdCompressor()
        val c1 = compressor.compress(uuid)
        val c2 = compressor.compress(uuid2)
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll("First: $c1, ", "second: $c2.") shouldBe "First: $uuid, second: $uuid2."
    }

    "UUID and ObjectId in same stream are both decompressed" {
        val compressor = IdCompressor()
        val cu = compressor.compress(uuid)
        val co = compressor.compress(objectId)
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll("uuid=$cu ", "oid=$co") shouldBe "uuid=$uuid oid=$objectId"
    }

    "same ID appearing multiple times is decompressed every occurrence" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(uuid)
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll("$compressed and $compressed") shouldBe "$uuid and $uuid"
    }

    // -------------------------------------------------------------------------
    // Flush behaviour
    // -------------------------------------------------------------------------

    "flush decompresses a token that remained entirely in the carry" {
        val compressor = IdCompressor()
        val compressed = compressor.compress(uuid)
        val decompressor = compressor.StreamingDecompressor()
        // Feed only the compressed token character by character, then flush
        val fed = compressed.map { it.toString() }.fold("") { acc, c -> acc + decompressor.feed(c) }
        val flushed = decompressor.flush()
        (fed + flushed) shouldBe uuid
    }

    "flush returns empty string when carry is empty after draining" {
        val compressor = IdCompressor()
        compressor.compress(uuid)
        val decompressor = compressor.StreamingDecompressor()
        // Feed text with no compressed tokens — carry should drain fully
        decompressor.feed("hello world, no ids here")
        decompressor.flush() shouldBe ""
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    "empty chunks are handled without errors" {
        val compressor = IdCompressor()
        compressor.compress(uuid)
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll("", "hello", "", " world", "") shouldBe "hello world"
    }

    // -------------------------------------------------------------------------
    // Realistic multi-chunk scenario: text + IDs interleaved across many chunks
    // -------------------------------------------------------------------------

    "realistic response with multiple IDs and surrounding text arriving in small chunks" {
        // Simulates a real LLM streaming response where the model echoes several
        // compressed IDs embedded in natural language, with chunks of varying size.
        val uuid2 = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        val compressor = IdCompressor()
        val cu1 = compressor.compress(uuid)
        val cu2 = compressor.compress(uuid2)
        val co1 = compressor.compress(objectId)

        // The full response the LLM would produce (with compressed aliases).
        val fullResponse = "Voici les identifiants :\n- $cu1 (profil A)\n- $cu2 (profil B)\n- $co1 (entité C)"
        val expected    = "Voici les identifiants :\n- $uuid (profil A)\n- $uuid2 (profil B)\n- $objectId (entité C)"

        // Chunk the response into groups of 3 characters to stress boundary splits.
        val chunks = fullResponse.chunked(3).toTypedArray()
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll(*chunks) shouldBe expected
    }

    "token split right at UI/OI prefix boundary across chunks" {
        // Specifically tests the case from the bug report:
        // chunk1 ends with 'UI', next chunks deliver the digits one by one.
        val compressor = IdCompressor()
        val cu = compressor.compress(uuid)
        // Construct a response where the token starts right at a chunk boundary.
        // e.g. chunk1 = "Profile: UI", then digits arrive one char at a time.
        val prefix = "Profile: "
        val chunks = mutableListOf<String>()
        chunks.add(prefix + cu.substring(0, 2))   // "Profile: UI"
        cu.substring(2).forEach { chunks.add(it.toString()) } // one char per chunk
        chunks.add(" is active.")                             // trailing text

        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll(*chunks.toTypedArray()) shouldBe "Profile: $uuid is active."
    }

    "two consecutive tokens with no separator arriving in mixed chunk sizes" {
        val uuid2 = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        val compressor = IdCompressor()
        val c1 = compressor.compress(uuid)
        val c2 = compressor.compress(uuid2)
        // Tokens adjacent: "<c1><c2>" — chunk every 2 chars.
        val fullResponse = "$c1$c2"
        val chunks = fullResponse.chunked(2).toTypedArray()
        val decompressor = compressor.StreamingDecompressor()
        decompressor.processAll(*chunks) shouldBe "$uuid$uuid2"
    }
})

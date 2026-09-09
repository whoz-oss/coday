package io.whozoss.agentos.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Unit tests for [IdCompressorService.compress] and [IdCompressorService.uncompress].
 *
 * Key invariant: `uncompress(compress(text, buffer), buffer)` must equal the original text
 * for any input containing UUIDs or MongoDB ObjectIds.
 */
class IdCompressorUnitSpec : StringSpec({

    val uuid1 = "550e8400-e29b-41d4-a716-446655440000"
    val uuid2 = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
    val objectId1 = "507f1f77bcf86cd799439011"
    val objectId2 = "507f191e810c19729de860ea"

    // -------------------------------------------------------------------------
    // Passthrough: no IDs in the text
    // -------------------------------------------------------------------------

    "compress returns text unchanged when no IDs are present" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress("hello world", buffer) shouldBe "hello world"
    }

    "compress returns text unchanged for blank input" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.compress("   ", buffer) shouldBe "   "
    }

    "uncompress returns text unchanged when no tokens are in the map" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        service.uncompress("hello world", buffer) shouldBe "hello world"
    }

    // -------------------------------------------------------------------------
    // UUID compression and decompression
    // -------------------------------------------------------------------------

    "compress replaces UUID with a short token" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val result = service.compress(uuid1, buffer)
        result shouldNotBe uuid1
        result shouldNotContain "-"          // UUID dashes gone
        result.length shouldNotBe uuid1.length
    }

    "compressed UUID token starts with UI prefix" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val result = service.compress(uuid1, buffer)
        result.startsWith(IdCompressorService.UUID_COMPRESSED_VALUE_PREFIX) shouldBe true
    }

    "uncompress(compress(uuid)) round-trips to the original UUID" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val compressed = service.compress(uuid1, buffer)
        service.uncompress(compressed, buffer) shouldBe uuid1
    }

    "compress is case-insensitive for UUIDs" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val lower = uuid1.lowercase()
        val upper = uuid1.uppercase()
        // Both forms should compress (first occurrence wins)
        service.compress(lower, buffer)
        val c2 = service.compress(upper, buffer)
        // The second form should also be replaced
        c2 shouldNotContain upper
    }

    // -------------------------------------------------------------------------
    // ObjectId compression and decompression
    // -------------------------------------------------------------------------

    "compress replaces ObjectId with a short token" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val result = service.compress(objectId1, buffer)
        result shouldNotBe objectId1
        result.length shouldNotBe objectId1.length
    }

    "compressed ObjectId token starts with OI prefix" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val result = service.compress(objectId1, buffer)
        result.startsWith(IdCompressorService.OBJECTID_COMPRESSED_VALUE_PREFIX) shouldBe true
    }

    "uncompress(compress(objectId)) round-trips to the original ObjectId" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val compressed = service.compress(objectId1, buffer)
        service.uncompress(compressed, buffer) shouldBe objectId1
    }

    // -------------------------------------------------------------------------
    // Multiple IDs in the same text
    // -------------------------------------------------------------------------

    "compress replaces all UUIDs in a text" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val text = "First: $uuid1 and second: $uuid2"
        val compressed = service.compress(text, buffer)
        compressed shouldNotContain uuid1
        compressed shouldNotContain uuid2
    }

    "uncompress restores all IDs in a text with multiple IDs" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val text = "First: $uuid1 and second: $uuid2"
        val compressed = service.compress(text, buffer)
        service.uncompress(compressed, buffer) shouldBe text
    }

    "compress handles UUID and ObjectId in the same text" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val text = "uuid=$uuid1 oid=$objectId1"
        val compressed = service.compress(text, buffer)
        compressed shouldNotContain uuid1
        compressed shouldNotContain objectId1
        service.uncompress(compressed, buffer) shouldBe text
    }

    // -------------------------------------------------------------------------
    // Repeated IDs: same ID compressed multiple times
    // -------------------------------------------------------------------------

    "same UUID compressed twice maps to the same token both times" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val text = "$uuid1 and $uuid1"
        val compressed = service.compress(text, buffer)
        compressed shouldNotContain uuid1
        service.uncompress(compressed, buffer) shouldBe text
    }

    "compressing the same UUID in a second call reuses the same token" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val first = service.compress(uuid1, buffer)
        val second = service.compress(uuid1, buffer)
        first shouldBe second
    }

    // -------------------------------------------------------------------------
    // Multiple compress() calls: global offset advances
    // -------------------------------------------------------------------------

    "two different UUIDs across separate compress() calls get distinct tokens" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val token1 = service.compress(uuid1, buffer)
        val token2 = service.compress(uuid2, buffer)
        token1 shouldNotBe token2
    }

    "uncompress restores IDs compressed across multiple compress() calls" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val c1 = service.compress("profile $uuid1", buffer)
        val c2 = service.compress("entity $objectId1", buffer)
        service.uncompress(c1, buffer) shouldBe "profile $uuid1"
        service.uncompress(c2, buffer) shouldBe "entity $objectId1"
    }

    "uncompress on a buffer with no map returns text unchanged" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        // compress() was never called — map is empty
        service.uncompress("UI0 OI1 some text", buffer) shouldBe "UI0 OI1 some text"
    }

    // -------------------------------------------------------------------------
    // Text surrounding IDs is preserved
    // -------------------------------------------------------------------------

    "surrounding text is preserved after round-trip" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val text = "The profile ($uuid1) has been updated successfully."
        val compressed = service.compress(text, buffer)
        compressed shouldContain "The profile ("
        compressed shouldContain ") has been updated successfully."
        service.uncompress(compressed, buffer) shouldBe text
    }

    "compress does not alter non-ID hexadecimal strings shorter than 24 chars" {
        val service = IdCompressorService()
        val buffer = service.newBuffer()
        val text = "short hex: deadbeef"
        service.compress(text, buffer) shouldBe text
    }
})

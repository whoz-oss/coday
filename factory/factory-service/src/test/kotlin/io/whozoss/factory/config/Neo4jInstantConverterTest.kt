package io.whozoss.factory.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.neo4j.driver.Values
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Unit tests of [Neo4jStringToInstantConverter].
 *
 * Verifies that the converter handles both nodes whose date properties are
 * stored as ISO-8601 String values and nodes whose date properties are stored
 * as native Neo4j ZonedDateTime values, so both can be read side-by-side
 * without a data migration.
 *
 * These tests are pure unit tests: no Spring context, no Neo4j harness.
 */
class Neo4jInstantConverterTest {

    private val converter = Neo4jStringToInstantConverter()

    @Test
    fun `converts a STRING value containing an ISO-8601 instant`() {
        val raw = "2026-10-05T15:06:25.536486Z"
        val value = Values.value(raw)

        val result = converter.convert(value)

        assertThat(result).isEqualTo(Instant.parse(raw))
    }

    @Test
    fun `converts a STRING value with offset (not UTC)`() {
        // Some legacy writers may have persisted with a +00:00 suffix instead of Z.
        val raw = "2026-01-15T08:30:00.000000000+00:00"
        val value = Values.value(raw)

        val result = converter.convert(value)

        assertThat(result).isEqualTo(Instant.parse("2026-01-15T08:30:00Z"))
    }

    @Test
    fun `converts a native ZonedDateTime value (current write format)`() {
        val expected = Instant.parse("2026-10-05T15:06:25.536486Z")
        // Values.value(ZonedDateTime) creates a ZonedDateTimeValue — the native type
        // that Spring Data Neo4j uses when writing Instant fields.
        val value = Values.value(ZonedDateTime.ofInstant(expected, ZoneOffset.UTC))

        val result = converter.convert(value)

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `a malformed STRING raises a DateTimeParseException`() {
        val malformed = Values.value("not-a-date")

        assertThatThrownBy { converter.convert(malformed) }
            .isInstanceOf(java.time.format.DateTimeParseException::class.java)
    }
}

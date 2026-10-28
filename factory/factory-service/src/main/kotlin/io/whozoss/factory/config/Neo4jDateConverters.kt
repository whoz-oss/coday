package io.whozoss.factory.config

import org.neo4j.driver.Value
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import java.time.Instant

/**
 * SDN reading converter: handles `DurableAgentAttempt` date properties stored
 * as ISO-8601 String values in Neo4j alongside nodes whose properties are
 * stored as native ZonedDateTime values.
 *
 * Spring Data Neo4j maps `java.time.Instant` through the driver's native
 * ZonedDateTime type. When the database returns a String property instead of a
 * ZonedDateTimeValue, SDN throws:
 *
 *   TypeMismatchDataAccessException: Could not convert "2026-10-05T15:06:25.536486Z"
 *   into java.time.Instant; ConversionFailedException: StringValue -> Instant;
 *   Uncoercible: Cannot coerce STRING to ZonedDateTime
 *
 * This converter accepts both formats so nodes with either storage format can
 * be read without a data migration:
 *   - STRING  → Instant.parse(value.asString())
 *   - Any temporal type → value.asZonedDateTime().toInstant()
 *
 * **Writing is deliberately left to SDN's default behaviour** (native
 * ZonedDateTime). New writes always use the native type and never regress to
 * String.
 */
@ReadingConverter
class Neo4jStringToInstantConverter : Converter<Value, Instant> {
    override fun convert(source: Value): Instant {
        return when (source.type().name()) {
            "STRING" -> Instant.parse(source.asString())
            else -> source.asZonedDateTime().toInstant()
        }
    }
}

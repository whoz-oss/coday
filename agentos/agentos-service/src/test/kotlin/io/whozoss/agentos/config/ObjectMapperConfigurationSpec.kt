package io.whozoss.agentos.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.RecurrenceDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import org.springframework.beans.factory.annotation.Autowired
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Guards the Jackson serialization contract for Java 8 date/time types.
 *
 * Uses the **Spring-injected** primary [ObjectMapper] bean — the same one that
 * serializes HTTP responses — so any misconfiguration in [ObjectMapperConfiguration]
 * is caught here.
 *
 * Regression context: when the `@Primary` ObjectMapper was introduced, it
 * replaced Spring Boot's auto-configured mapper but omitted
 * `WRITE_DATES_AS_TIMESTAMPS = false`. The result was:
 * - `LocalDate` serialized as `[2026,8,4]` instead of `"2026-08-04"`
 * - `Instant` serialized as `1787223600.0` instead of `"2026-08-14T09:00:00Z"`
 * Both broke the Angular frontend silently (empty date fields, 1970 display).
 *
 * The `embedded-neo4j` profile is required because `@SpringBootTest` loads the full
 * application context, which includes the persistence layer. Without it the context
 * fails to start because no Neo4j connection is available.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class ObjectMapperConfigurationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var mapper: ObjectMapper

    init {
        // ---------------------------------------------------------------------
        // LocalDate — must serialize as ISO-8601 string, never as array
        // ---------------------------------------------------------------------

        "LocalDate serializes as ISO-8601 string, not as array" {
            val date = LocalDate.of(2026, 8, 4)
            mapper.writeValueAsString(date) shouldBe "\"2026-08-04\""
        }

        // ---------------------------------------------------------------------
        // Instant — must serialize as ISO-8601 string, never as numeric epoch
        // ---------------------------------------------------------------------

        "Instant serializes as ISO-8601 string, not as numeric epoch" {
            val instant = Instant.parse("2026-08-14T09:00:00Z")
            mapper.writeValueAsString(instant) shouldBe "\"2026-08-14T09:00:00Z\""
        }

        // ---------------------------------------------------------------------
        // ScheduledPromptDto — end-to-end serialization of all date fields
        // ---------------------------------------------------------------------

        "ScheduledPromptDto serializes all date fields as ISO-8601 strings" {
            val dto = ScheduledPromptDto(
                id = UUID.randomUUID(),
                agentConfigId = UUID.randomUUID(),
                promptContent = "test",
                name = "test-prompt",
                recurrence = RecurrenceDto(
                    unit = SchedulerUnit.WEEK,
                    days = listOf(DayOfWeek.THURSDAY),
                    timeUtc = LocalTime.of(11, 0),
                ),
                planning = PlanningDto(
                    startDate = LocalDate.of(2026, 8, 4),
                    endType = SchedulerEndType.NEVER,
                ),
                enabled = true,
                nextRunAt = Instant.parse("2026-08-14T09:00:00Z"),
                lastRunAt = Instant.parse("2026-08-07T09:00:00Z"),
                createdOn = Instant.parse("2026-08-01T10:00:00Z"),
                updatedOn = Instant.parse("2026-08-01T12:00:00Z"),
            )

            val tree = mapper.readTree(mapper.writeValueAsString(dto))

            // planning.startDate — must be string, not array
            tree.at("/planning/startDate").isTextual shouldBe true
            tree.at("/planning/startDate").asText() shouldBe "2026-08-04"

            // nextRunAt — must be string, not number
            tree.at("/nextRunAt").isTextual shouldBe true
            tree.at("/nextRunAt").asText() shouldBe "2026-08-14T09:00:00Z"

            // lastRunAt — must be string, not number
            tree.at("/lastRunAt").isTextual shouldBe true
            tree.at("/lastRunAt").asText() shouldBe "2026-08-07T09:00:00Z"

            // createdOn, updatedOn — must be strings
            tree.at("/createdOn").isTextual shouldBe true
            tree.at("/updatedOn").isTextual shouldBe true

            // recurrence.timeUtc — must be "11:00" (HH:mm pattern)
            tree.at("/recurrence/timeUtc").asText() shouldBe "11:00"
        }

        // ---------------------------------------------------------------------
        // Round-trip: ISO string deserializes back correctly
        // ---------------------------------------------------------------------

        "PlanningDto round-trips through JSON without loss" {
            val original = PlanningDto(
                startDate = LocalDate.of(2026, 8, 4),
                endType = SchedulerEndType.ON_DATE,
                endDate = LocalDate.of(2026, 12, 31),
            )
            val json = mapper.writeValueAsString(original)
            val restored = mapper.readValue(json, PlanningDto::class.java)
            restored shouldBe original
        }
    }
}

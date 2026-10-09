package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.factorybridge.persistence.FactorySseHighWaterMarkStore
import io.whozoss.agentos.plugins.factorybridge.persistence.SseHighWaterMark
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * Restart-safe persistence of the bridge-side SSE high-water mark.
 */
class FactorySseHighWaterMarkStoreSpec : StringSpec({
    val t0 = Instant.parse("2030-01-01T00:00:00Z")
    val mapper = jacksonObjectMapper()

    fun withStore(block: (java.nio.file.Path) -> Unit) {
        val dir = Files.createTempDirectory("factory-bridge-hwm")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun store(dir: java.nio.file.Path) = FactorySseHighWaterMarkStore.open(dir.toAbsolutePath().toString(), mapper)

    "the high-water mark is persisted and read back after a simulated restart" {
        withStore { dir ->
            val caseId = UUID.randomUUID()
            val attempt = "attempt-1"
            store(dir).advance(caseId, attempt, t0.plusSeconds(5), "event-005") shouldBe SseHighWaterMark(t0.plusSeconds(5), "event-005")

            val restarted = store(dir)
            restarted.get(caseId, attempt) shouldBe SseHighWaterMark(t0.plusSeconds(5), "event-005")
        }
    }

    "marks are scoped per (case, attempt)" {
        withStore { dir ->
            val firstCase = UUID.randomUUID()
            val secondCase = UUID.randomUUID()
            val s = store(dir)
            s.advance(firstCase, "attempt-1", t0.plusSeconds(1), "a")
            s.advance(secondCase, "attempt-1", t0.plusSeconds(2), "b")
            s.advance(firstCase, "attempt-2", t0.plusSeconds(3), "c")

            s.get(firstCase, "attempt-1") shouldBe SseHighWaterMark(t0.plusSeconds(1), "a")
            s.get(secondCase, "attempt-1") shouldBe SseHighWaterMark(t0.plusSeconds(2), "b")
            s.get(firstCase, "attempt-2") shouldBe SseHighWaterMark(t0.plusSeconds(3), "c")
        }
    }

    "the mark never moves backwards and deduplicates replayed events" {
        withStore { dir ->
            val caseId = UUID.randomUUID()
            val attempt = "attempt-1"
            val s = store(dir)
            s.advance(caseId, attempt, t0.plusSeconds(10), "event-010")
            // Older timestamp and same-timestamp/older id are both ignored.
            s.advance(caseId, attempt, t0.plusSeconds(9), "event-099") shouldBe SseHighWaterMark(t0.plusSeconds(10), "event-010")
            s.advance(caseId, attempt, t0.plusSeconds(10), "event-001") shouldBe SseHighWaterMark(t0.plusSeconds(10), "event-010")

            s.isDuplicate(caseId, attempt, t0.plusSeconds(10), "event-010") shouldBe true
            s.isDuplicate(caseId, attempt, t0.plusSeconds(10), "event-000") shouldBe true
            s.isDuplicate(caseId, attempt, t0.plusSeconds(10), "event-011") shouldBe false
            s.isDuplicate(caseId, attempt, t0.plusSeconds(11), "event-000") shouldBe false
            s.isDuplicate(caseId, "other-attempt", t0.plusSeconds(1), "event-000") shouldBe false
        }
    }

    "a corrupt file is tolerated and starts empty" {
        withStore { dir ->
            Files.writeString(dir.resolve("sse-high-water-marks.json"), "{ not json")
            val s = store(dir)
            s.get(UUID.randomUUID(), "attempt") shouldBe null
        }
    }
})

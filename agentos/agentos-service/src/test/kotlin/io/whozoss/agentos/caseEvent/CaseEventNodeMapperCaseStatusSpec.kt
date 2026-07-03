package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

class CaseEventNodeMapperCaseStatusSpec :
    StringSpec({

        val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
        val nodeMapper = CaseEventNodeMapper(MessageContentSerializer(mapper))

        "CaseStatusEvent(PENDING) survives the fromDomain/toDomain round-trip" {
            val original =
                CaseStatusEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:00:00Z"),
                    status = CaseStatus.PENDING,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as CaseStatusEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.status shouldBe CaseStatus.PENDING
        }

        "CaseStatusEvent(RUNNING) survives the fromDomain/toDomain round-trip" {
            val original =
                CaseStatusEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:01:00Z"),
                    status = CaseStatus.RUNNING,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as CaseStatusEvent

            roundTripped.status shouldBe CaseStatus.RUNNING
        }

        "CaseStatusEvent(IDLE) survives the fromDomain/toDomain round-trip" {
            val original =
                CaseStatusEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:02:00Z"),
                    status = CaseStatus.IDLE,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as CaseStatusEvent

            roundTripped.status shouldBe CaseStatus.IDLE
        }

        "CaseStatusEvent(KILLED) survives the fromDomain/toDomain round-trip" {
            val original =
                CaseStatusEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:03:00Z"),
                    status = CaseStatus.KILLED,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as CaseStatusEvent

            roundTripped.status shouldBe CaseStatus.KILLED
        }

        "CaseStatusEvent(ERROR) survives the fromDomain/toDomain round-trip" {
            val original =
                CaseStatusEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:04:00Z"),
                    status = CaseStatus.ERROR,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as CaseStatusEvent

            roundTripped.status shouldBe CaseStatus.ERROR
        }
    })

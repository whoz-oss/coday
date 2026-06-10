package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.ErrorEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

class CaseEventNodeMapperNotificationSpec :
    StringSpec({

        val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
        val nodeMapper = CaseEventNodeMapper(MessageContentSerializer(mapper))

        "WarnEvent survives the fromDomain/toDomain round-trip" {
            val original =
                WarnEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:00:00Z"),
                    message = "Low memory warning",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as WarnEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.message shouldBe original.message
        }

        "ErrorEvent survives the fromDomain/toDomain round-trip" {
            val original =
                ErrorEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:01:00Z"),
                    message = "Unrecoverable failure: connection refused",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ErrorEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.message shouldBe original.message
        }
    })

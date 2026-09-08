package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.SubCaseFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseOutcome
import io.whozoss.agentos.sdk.caseEvent.SubCaseStartedEvent
import java.util.UUID

class CaseEventNodeMapperDelegationSpec : StringSpec({
    val mapper = CaseEventNodeMapper(MessageContentSerializer(jacksonObjectMapper().registerKotlinModule()))

    "SubCaseStartedEvent round-trips all delegation fields" {
        val original = SubCaseStartedEvent(namespaceId = UUID.randomUUID(), caseId = UUID.randomUUID(), delegationId = UUID.randomUUID(), toolRequestId = "parent-tool-request", subCaseId = UUID.randomUUID(), agentName = "worker", task = "Investigate", resumed = true)
        val roundTripped = mapper.toDomain(mapper.fromDomain(original)) as SubCaseStartedEvent
        roundTripped.delegationId shouldBe original.delegationId
        roundTripped.toolRequestId shouldBe "parent-tool-request"
        roundTripped.subCaseId shouldBe original.subCaseId
        roundTripped.agentName shouldBe original.agentName
        roundTripped.task shouldBe original.task
        roundTripped.resumed shouldBe true
    }

    "SubCaseFinishedEvent round-trips outcome and error type" {
        val original = SubCaseFinishedEvent(namespaceId = UUID.randomUUID(), caseId = UUID.randomUUID(), delegationId = UUID.randomUUID(), toolRequestId = "parent-tool-request", subCaseId = UUID.randomUUID(), agentName = "worker", outcome = SubCaseOutcome.TIMEOUT, errorType = "TIMEOUT")
        val roundTripped = mapper.toDomain(mapper.withRemoved(mapper.fromDomain(original), true)) as SubCaseFinishedEvent
        roundTripped.delegationId shouldBe original.delegationId
        roundTripped.toolRequestId shouldBe "parent-tool-request"
        roundTripped.subCaseId shouldBe original.subCaseId
        roundTripped.outcome shouldBe SubCaseOutcome.TIMEOUT
        roundTripped.errorType shouldBe "TIMEOUT"
        roundTripped.metadata.removed shouldBe true
    }
})

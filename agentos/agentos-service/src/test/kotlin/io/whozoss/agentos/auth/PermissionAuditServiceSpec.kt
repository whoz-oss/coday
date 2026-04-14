package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher

class PermissionAuditServiceSpec : StringSpec() {

    private val callerId = "user-123"
    private val namespaceId = "ns-456"
    private val toolName = "readFile"
    private val caseId = "case-789"

    init {
        "logGranted publishes PermissionAuditEvent with granted=true" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)

            service.logGranted(callerId, namespaceId, toolName, caseId)

            verify(exactly = 1) { publisher.publishEvent(any<PermissionAuditEvent>()) }
            val captured = eventSlot.captured
            captured.callerId shouldBe callerId
            captured.namespaceId shouldBe namespaceId
            captured.toolName shouldBe toolName
            captured.caseId shouldBe caseId
            captured.granted shouldBe true
        }

        "logDenied publishes PermissionAuditEvent with granted=false" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)

            service.logDenied(callerId, namespaceId, toolName, caseId)

            verify(exactly = 1) { publisher.publishEvent(any<PermissionAuditEvent>()) }
            val captured = eventSlot.captured
            captured.callerId shouldBe callerId
            captured.namespaceId shouldBe namespaceId
            captured.toolName shouldBe toolName
            captured.caseId shouldBe caseId
            captured.granted shouldBe false
        }
    }
}

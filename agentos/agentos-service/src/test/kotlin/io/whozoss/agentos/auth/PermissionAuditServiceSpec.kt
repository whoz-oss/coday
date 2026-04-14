package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
    private val toolCategory = "READ_ONLY"
    private val callerDisplayName = "Alice Smith"

    init {
        "logGranted publishes PermissionAuditEvent with granted=true and all context fields" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)

            service.logGranted(callerId, namespaceId, toolName, caseId, toolCategory, callerDisplayName)

            verify(exactly = 1) { publisher.publishEvent(any<PermissionAuditEvent>()) }
            val captured = eventSlot.captured
            captured.callerId shouldBe callerId
            captured.namespaceId shouldBe namespaceId
            captured.toolName shouldBe toolName
            captured.caseId shouldBe caseId
            captured.granted shouldBe true
            captured.reason shouldBe null
            captured.toolCategory shouldBe toolCategory
            captured.callerDisplayName shouldBe callerDisplayName
            captured.timestamp shouldNotBe null
        }

        "logDenied publishes PermissionAuditEvent with granted=false and reason" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)
            val reason = "Permission denied for tool 'readFile'"

            service.logDenied(callerId, namespaceId, toolName, caseId, reason, toolCategory, callerDisplayName)

            verify(exactly = 1) { publisher.publishEvent(any<PermissionAuditEvent>()) }
            val captured = eventSlot.captured
            captured.callerId shouldBe callerId
            captured.namespaceId shouldBe namespaceId
            captured.toolName shouldBe toolName
            captured.caseId shouldBe caseId
            captured.granted shouldBe false
            captured.reason shouldBe reason
            captured.toolCategory shouldBe toolCategory
            captured.callerDisplayName shouldBe callerDisplayName
            captured.timestamp shouldNotBe null
        }

        "logGranted event contains non-null timestamp" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)

            service.logGranted(callerId, namespaceId, toolName, caseId, toolCategory, callerDisplayName)

            eventSlot.captured.timestamp shouldNotBe null
        }

        "logGranted event contains toolCategory and callerDisplayName" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)

            service.logGranted(callerId, namespaceId, toolName, caseId, "WRITE", "Bob Builder")

            val captured = eventSlot.captured
            captured.toolCategory shouldBe "WRITE"
            captured.callerDisplayName shouldBe "Bob Builder"
        }

        "logDenied event contains reason" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)
            val reason = "Insufficient permissions for DANGEROUS tool"

            service.logDenied(callerId, namespaceId, toolName, caseId, reason, toolCategory, callerDisplayName)

            eventSlot.captured.reason shouldBe reason
        }

        "callerDisplayName null is correctly handled in logGranted" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)

            service.logGranted(callerId, namespaceId, toolName, caseId, toolCategory, null)

            val captured = eventSlot.captured
            captured.callerDisplayName shouldBe null
            captured.granted shouldBe true
        }

        "callerDisplayName null is correctly handled in logDenied" {
            val eventSlot = slot<PermissionAuditEvent>()
            val publisher = mockk<ApplicationEventPublisher> {
                every { publishEvent(capture(eventSlot)) } returns Unit
            }
            val service = PermissionAuditService(publisher)

            service.logDenied(callerId, namespaceId, toolName, caseId, "denied", toolCategory, null)

            val captured = eventSlot.captured
            captured.callerDisplayName shouldBe null
            captured.granted shouldBe false
        }
    }
}

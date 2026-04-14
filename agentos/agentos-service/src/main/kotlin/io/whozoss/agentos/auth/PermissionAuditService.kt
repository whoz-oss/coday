package io.whozoss.agentos.auth

import mu.KLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class PermissionAuditService(
    private val eventPublisher: ApplicationEventPublisher,
) {
    companion object : KLogging()

    fun logGranted(callerId: String, namespaceId: String, toolName: String, caseId: String) {
        logger.info { "PERMISSION_GRANTED user=$callerId ns=$namespaceId tool=$toolName case=$caseId" }
        eventPublisher.publishEvent(PermissionAuditEvent(callerId, namespaceId, toolName, caseId, granted = true))
    }

    fun logDenied(callerId: String, namespaceId: String, toolName: String, caseId: String) {
        logger.warn { "PERMISSION_DENIED user=$callerId ns=$namespaceId tool=$toolName case=$caseId" }
        eventPublisher.publishEvent(PermissionAuditEvent(callerId, namespaceId, toolName, caseId, granted = false))
    }
}

data class PermissionAuditEvent(
    val callerId: String,
    val namespaceId: String,
    val toolName: String,
    val caseId: String,
    val granted: Boolean,
)

package io.whozoss.agentos.auth

import java.time.Instant
import mu.KLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class PermissionAuditService(
    private val eventPublisher: ApplicationEventPublisher,
) {
    companion object : KLogging()

    fun logGranted(
        callerId: String,
        namespaceId: String,
        toolName: String,
        caseId: String,
        toolCategory: String,
        callerDisplayName: String?,
    ) {
        val timestamp = Instant.now()
        logger.info {
            "PERMISSION_GRANTED user=$callerId ns=$namespaceId tool=$toolName case=$caseId " +
                "category=$toolCategory caller=$callerDisplayName ts=$timestamp"
        }
        eventPublisher.publishEvent(
            PermissionAuditEvent(
                callerId = callerId,
                namespaceId = namespaceId,
                toolName = toolName,
                caseId = caseId,
                granted = true,
                reason = null,
                toolCategory = toolCategory,
                callerDisplayName = callerDisplayName,
                timestamp = timestamp,
            ),
        )
    }

    fun logDenied(
        callerId: String,
        namespaceId: String,
        toolName: String,
        caseId: String,
        reason: String,
        toolCategory: String,
        callerDisplayName: String?,
    ) {
        val timestamp = Instant.now()
        logger.warn {
            "PERMISSION_DENIED user=$callerId ns=$namespaceId tool=$toolName case=$caseId " +
                "reason=$reason category=$toolCategory caller=$callerDisplayName ts=$timestamp"
        }
        eventPublisher.publishEvent(
            PermissionAuditEvent(
                callerId = callerId,
                namespaceId = namespaceId,
                toolName = toolName,
                caseId = caseId,
                granted = false,
                reason = reason,
                toolCategory = toolCategory,
                callerDisplayName = callerDisplayName,
                timestamp = timestamp,
            ),
        )
    }
}

data class PermissionAuditEvent(
    val callerId: String,
    val namespaceId: String,
    val toolName: String,
    val caseId: String,
    val granted: Boolean,
    val reason: String?,
    val toolCategory: String,
    val callerDisplayName: String?,
    val timestamp: Instant,
)

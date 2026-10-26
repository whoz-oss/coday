package io.whozoss.factory.agentattempt.domain

import java.time.Instant

/** Durable authorization for one verified service integration to submit one Factory attempt result. */
data class DurableAttemptIntegrationBinding(
    val attemptId: String,
    val serviceIdentityId: String,
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val briefHash: String,
    val createdAt: Instant = Instant.now(),
)

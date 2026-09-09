package io.whozoss.agentos.scheduledPrompt

import java.time.Instant
import java.util.UUID

/**
 * Thrown by [ScheduledPromptRunRepository.insert] when a run for the same
 * `(scheduledPromptId, scheduledFor)` slot already exists.
 *
 * This is the expected outcome of a concurrent scheduler tick claiming the same slot —
 * the caller should treat it as a harmless no-op and still advance [ScheduledPrompt.nextRunAt].
 */
class DuplicateRunException(
    scheduledPromptId: UUID,
    scheduledFor: Instant,
) : Exception("Run already exists for scheduledPromptId=$scheduledPromptId scheduledFor=$scheduledFor")

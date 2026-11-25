package io.whozoss.agentos.caseEvent

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * A teammate mentioned [userId] with `@handle` in a message of [caseId].
 *
 * Not a case event: it is addressed to one person, belongs to no history and is never persisted. It travels once, on
 * the stream of that user ([GlobalCaseEventSseController]).
 */
data class CaseMention(
    val id: UUID = UUID.randomUUID(),
    /** The person mentioned, who receives it. */
    val userId: UUID,
    val caseId: UUID,
    val namespaceId: UUID,
    /** The author of the message, as a user id. */
    val byUserId: String,
    val byName: String,
    /** The start of the message, enough to tell what it is about. */
    val snippet: String,
    val timestamp: Instant = Instant.now(),
)

/**
 * Process-local stream of [CaseMention]s. Replays nothing: a user who is not connected when the message is posted is
 * not notified, the mention stays readable in the case.
 */
@Component
class CaseMentionBroadcaster {
    private val mentions =
        MutableSharedFlow<CaseMention>(
            replay = 0,
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Every mention, whoever it is for: a consumer keeps the ones addressed to its user. */
    fun events(): SharedFlow<CaseMention> = mentions.asSharedFlow()

    fun publish(mention: CaseMention) {
        mentions.tryEmit(mention)
    }

    private companion object {
        const val BUFFER_CAPACITY = 64
    }
}

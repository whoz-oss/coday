package io.whozoss.agentos.caseEvent

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is looking at a shared case right now, process-local.
 *
 * Each open collaborative event stream of a case ([CaseEventSseController]) is one connection, tied to the user
 * who opened it. A user with the case open in two tabs holds two connections but is present once: [usersOf]
 * answers distinct users. Closing one tab leaves the user present as long as another stays open.
 *
 * Nothing is persisted: presence is the set of live connections, so it is empty after a restart and rebuilt as
 * the clients reconnect.
 */
@Component
class CasePresenceRegistry {
    /** Case id to (connection id to user id). */
    private val connections = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, UUID>>()

    /**
     * The id of every case whose presence just changed. A burst that overflows the buffer only drops the oldest
     * signals: a listener reads [usersOf] when it is woken up, so a later signal still carries the final state.
     */
    private val changed =
        MutableSharedFlow<UUID>(
            replay = 0,
            extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Registers a connection of [userId] on [caseId], and returns its id, to hand back to [leave]. */
    fun join(
        caseId: UUID,
        userId: UUID,
    ): UUID {
        val connectionId = UUID.randomUUID()
        connections.compute(caseId) { _, current ->
            (current ?: ConcurrentHashMap<UUID, UUID>()).also { it[connectionId] = userId }
        }
        changed.tryEmit(caseId)
        return connectionId
    }

    /** Forgets a connection. Leaving twice, or an unknown connection, does nothing. */
    fun leave(
        caseId: UUID,
        connectionId: UUID,
    ) {
        var removed = false
        connections.computeIfPresent(caseId) { _, current ->
            removed = current.remove(connectionId) != null
            if (current.isEmpty()) null else current
        }
        if (removed) changed.tryEmit(caseId)
    }

    /** The users with at least one open connection on [caseId]. */
    fun usersOf(caseId: UUID): Set<UUID> = connections[caseId]?.values?.toSet().orEmpty()

    /** Emits [caseId] each time the presence of that case changes, and nothing for the other cases. */
    fun changes(caseId: UUID): Flow<UUID> = changed.filter { it == caseId }

    private companion object {
        const val CHANGE_BUFFER_CAPACITY = 64
    }
}

package io.whozoss.agentos.caseEvent

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks how many open "my events" SSE connections ([GlobalCaseEventSseController])
 * each user currently has, across all their tabs/devices.
 *
 * Consulted by [PushNotificationDispatcher] before sending a push notification: a user
 * already watching the companion live doesn't need a duplicate OS push for the same
 * event. Multi-tab safe (a count, not a boolean) — closing one tab while another stays
 * open must not flip the user back to "no active session."
 */
@Component
class ActiveCompanionSessionRegistry {
    private val counts = ConcurrentHashMap<UUID, AtomicInteger>()

    fun markConnected(userId: UUID) {
        counts.computeIfAbsent(userId) { AtomicInteger(0) }.incrementAndGet()
    }

    fun markDisconnected(userId: UUID) {
        // Returning null from the remapping function atomically removes the entry —
        // avoids a separate check-then-remove race between two disconnecting tabs.
        counts.computeIfPresent(userId) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }

    fun isActive(userId: UUID): Boolean = (counts[userId]?.get() ?: 0) > 0
}

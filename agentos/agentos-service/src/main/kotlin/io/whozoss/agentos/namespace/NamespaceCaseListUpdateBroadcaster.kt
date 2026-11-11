package io.whozoss.agentos.namespace

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Process-local update stream for the authenticated namespace case list. */
@Component
class NamespaceCaseListUpdateBroadcaster {
    private val streams = ConcurrentHashMap<UUID, MutableSharedFlow<Unit>>()

    /**
     * Every namespace's updates on one flow, each carrying its namespace: what a client following
     * several case lists at once subscribes to (the global case-event stream, #1616), instead of
     * resolving up front which namespaces to follow and missing the ones that appear later.
     *
     * Unlike the per-namespace flows, distinct values do not coalesce, hence a deeper buffer.
     */
    private val anyNamespace =
        MutableSharedFlow<UUID>(
            replay = 0,
            extraBufferCapacity = ANY_NAMESPACE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    fun events(namespaceId: UUID): MutableSharedFlow<Unit> = stream(namespaceId)

    /** The id of every namespace whose case list changed, whichever namespace it is. */
    fun allEvents(): SharedFlow<UUID> = anyNamespace.asSharedFlow()

    fun publishCaseCreated(namespaceId: UUID) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        emit(namespaceId)
                    }
                },
            )
        } else {
            emit(namespaceId)
        }
    }

    /** Tells clients that a case of the namespace changed (e.g. its summary), so they reload their list. */
    fun publishCaseUpdated(namespaceId: UUID) = publishCaseCreated(namespaceId)

    private fun emit(namespaceId: UUID) {
        streams[namespaceId]?.tryEmit(Unit)
        anyNamespace.tryEmit(namespaceId)
    }

    private fun stream(namespaceId: UUID): MutableSharedFlow<Unit> =
        streams.computeIfAbsent(namespaceId) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = BUFFER_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

    companion object {
        private const val BUFFER_CAPACITY = 1
        private const val ANY_NAMESPACE_BUFFER_CAPACITY = 64
    }
}

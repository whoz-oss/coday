package io.whozoss.agentos.namespace

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Process-local update stream for the authenticated namespace case list. */
@Component
class NamespaceCaseListUpdateBroadcaster {
    private val streams = ConcurrentHashMap<UUID, MutableSharedFlow<Unit>>()

    fun events(namespaceId: UUID): MutableSharedFlow<Unit> = stream(namespaceId)

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

    private fun emit(namespaceId: UUID) {
        streams[namespaceId]?.tryEmit(Unit)
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
    }
}

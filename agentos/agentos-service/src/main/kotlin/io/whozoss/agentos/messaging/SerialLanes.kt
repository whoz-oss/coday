package io.whozoss.agentos.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import mu.KLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs submitted work one item at a time per key, in submission order, and different keys in parallel.
 *
 * Messages of one chat must be taken in, and replies sent, in their order, while a slow chat must not hold
 * the others back. A lane is a channel with one consumer, opened on the first item of its key and kept for the
 * life of [scope]: a lane per chat seen since startup, a few bytes each.
 *
 * Work that throws is logged and the lane goes on with the next item.
 *
 * The work runs on [LANE_DISPATCHER], whatever the dispatcher of [scope]: it blocks (a transcription waiting for
 * its slot, a send waiting out a rate limit), and chats must not take the threads the rest of AgentOS blocks on.
 */
class SerialLanes<K : Any>(
    private val scope: CoroutineScope,
    private val name: String,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lanes = ConcurrentHashMap<K, SendChannel<suspend () -> Unit>>()

    /** Queues [work] on the lane of [key]. False, and [work] dropped, when that lane is full. */
    fun submit(
        key: K,
        work: suspend () -> Unit,
    ): Boolean =
        lanes.computeIfAbsent(key, ::open).trySend(work).isSuccess.also { queued ->
            if (!queued) logger.warn { "[$name] lane $key is full, an item was dropped" }
        }

    private fun open(key: K): SendChannel<suspend () -> Unit> =
        Channel<suspend () -> Unit>(capacity).also { channel ->
            scope.launch(LANE_DISPATCHER) {
                for (work in channel) {
                    try {
                        work()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "[$name] an item of lane $key failed" }
                    }
                }
            }
        }

    companion object : KLogging() {
        const val DEFAULT_CAPACITY = 100

        /**
         * The threads of every lane, together: a view of [Dispatchers.IO] of its own, which neither takes more
         * than [LANE_THREADS] threads nor counts against those of [Dispatchers.IO]. The long polls of the bots stay
         * on their own scopes: one per bot, waiting, they would fill it.
         */
        private const val LANE_THREADS = 32
        val LANE_DISPATCHER: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(LANE_THREADS)
    }
}

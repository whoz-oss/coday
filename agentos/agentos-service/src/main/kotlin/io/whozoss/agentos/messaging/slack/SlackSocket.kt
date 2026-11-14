package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.scheduledPrompt.exponentialBackoffMs
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The Socket Mode connection of one Slack app, from [start] to [stop]: the app's events and interactions arrive
 * over a WebSocket the instance opens, so the instance needs no public URL, as with Telegram's long polling.
 *
 * Every envelope is acknowledged as soon as it arrives, then handed to [onEnvelope], which must not block. Slack
 * refreshes connections every few hours and warns before: the socket then opens a new connection, and the old
 * one closes on its own. A refused app-level token stops it ([MessagingBotStatus.TOKEN_REJECTED]); anything else
 * is retried with a growing delay.
 */
class SlackSocket(
    private val appToken: String,
    private val web: SlackWebClient,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val name: String,
    private val onEnvelope: (SlackEvents.Frame.Envelope) -> Unit,
    private val onStatus: (MessagingBotStatus) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val current = AtomicReference<WebSocket?>()
    private val failures = AtomicInteger()

    /** True while a connection is being opened: a disconnect and the close that follows it ask for one each. */
    private val connecting = AtomicBoolean(false)

    fun start() {
        connecting.set(true)
        scope.launch { connect() }
    }

    fun stop() {
        stopped.set(true)
        scope.cancel()
        current.getAndSet(null)?.close(NORMAL_CLOSURE, "stopped")
    }

    /** Opens a connection, retrying until Slack gives one, the token is refused, or the socket stops. */
    private suspend fun connect() {
        try {
            openConnection()
        } finally {
            connecting.set(false)
        }
    }

    private suspend fun openConnection() {
        while (!stopped.get()) {
            try {
                val url = web.openConnection(appToken)
                val socket = httpClient.newWebSocket(Request.Builder().url(url).build(), Listener())
                current.getAndSet(socket)?.close(NORMAL_CLOSURE, "replaced")
                // A stop while the connection opened found no socket to close: close this one.
                if (stopped.get()) current.getAndSet(null)?.close(NORMAL_CLOSURE, "stopped")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: SlackApiException) {
                if (e.tokenRejected) {
                    logger.error { "[Slack] $name: app-level token rejected (${e.error}), the bot stops until attached again" }
                    report(MessagingBotStatus.TOKEN_REJECTED)
                    stopped.set(true)
                    return
                }
                logger.warn { "[Slack] $name: apps.connections.open failed (${e.error}), retrying" }
                report(MessagingBotStatus.UNREACHABLE)
                delay(e.retryAfterSeconds?.let { it * 1000L } ?: backoff(failures.incrementAndGet()))
            } catch (e: IOException) {
                logger.warn { "[Slack] $name: Slack not reachable (${e.javaClass.simpleName}), retrying" }
                report(MessagingBotStatus.UNREACHABLE)
                delay(backoff(failures.incrementAndGet()))
            }
        }
    }

    /** Reports [status] while the socket runs: a call still in flight at the stop reports nothing. */
    private fun report(status: MessagingBotStatus) {
        if (!stopped.get()) onStatus(status)
    }

    private fun reconnect(afterMillis: Long) {
        if (!stopped.get() && connecting.compareAndSet(false, true)) {
            scope.launch {
                delay(afterMillis)
                connect()
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            when (val frame = SlackEvents.read(objectMapper, text)) {
                is SlackEvents.Frame.Hello -> {
                    failures.set(0)
                    logger.info { "[Slack] receiving as $name" }
                    report(MessagingBotStatus.RUNNING)
                }
                is SlackEvents.Frame.Envelope -> {
                    webSocket.send(frame.ack)
                    onEnvelope(frame)
                }
                is SlackEvents.Frame.Disconnect -> {
                    logger.debug { "[Slack] $name: Slack closes the connection (${frame.reason}), opening another" }
                    if (webSocket == current.get()) reconnect(0)
                }
                SlackEvents.Frame.Other -> Unit
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (webSocket == current.get() && !stopped.get()) {
                logger.warn { "[Slack] $name: connection lost (${t.javaClass.simpleName}), reconnecting" }
                report(MessagingBotStatus.UNREACHABLE)
                reconnect(backoff(failures.incrementAndGet()))
            }
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            if (webSocket == current.get() && !stopped.get()) reconnect(0)
        }
    }

    private fun backoff(failures: Int): Long = exponentialBackoffMs(failures)

    companion object : KLogging() {
        private const val NORMAL_CLOSURE = 1000
    }
}

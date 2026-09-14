package io.whozoss.agentos.plugins.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captures every log event emitted under the `io.whozoss.agentos.plugins.mcp` logger hierarchy
 * for the duration of [block], so that specs can assert on WARN emission and on the absence of
 * secrets in log output. Test-only helper.
 */
class LogCapture private constructor() {
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(PLUGIN_LOGGER_NAME) as Logger

    /** Formatted messages of every captured event, in emission order. */
    val messages: List<String>
        get() = appender.list.map { it.formattedMessage }

    /** Formatted messages of captured events at [level]. */
    fun messagesAt(level: Level): List<String> =
        appender.list.filter { it.level == level }.map { it.formattedMessage }

    private fun start() {
        appender.start()
        logger.addAppender(appender)
    }

    private fun stop() {
        logger.detachAppender(appender)
        appender.stop()
    }

    companion object {
        private const val PLUGIN_LOGGER_NAME = "io.whozoss.agentos.plugins.mcp"

        /** Runs [block] with capture enabled and returns the capture for assertions. */
        fun capturing(block: () -> Unit): LogCapture {
            val capture = LogCapture()
            capture.start()
            try {
                block()
            } finally {
                capture.stop()
            }
            return capture
        }
    }
}

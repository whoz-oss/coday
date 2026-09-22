package io.whozoss.agentos.plugins.http.testing

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Every line logged through SLF4J during the test run, all levels included (TRACE too). */
object CapturedLogs {
    val lines = CopyOnWriteArrayList<String>()

    fun clear(): Unit = lines.clear()
}

/**
 * Test-only SLF4J binding (registered in `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`) so that
 * tests can assert on what the plugin logs, in particular that no secret ever reaches a log line.
 */
class CapturingLoggerProvider : SLF4JServiceProvider {

    private val loggers = ConcurrentHashMap<String, Logger>()
    private val markerFactory = BasicMarkerFactory()
    private val mdcAdapter = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory =
        ILoggerFactory { name -> loggers.computeIfAbsent(name, ::CapturingLogger) }

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize(): Unit = Unit

    private class CapturingLogger(loggerName: String) : LegacyAbstractLogger() {
        init {
            name = loggerName
        }

        override fun isTraceEnabled(): Boolean = true
        override fun isDebugEnabled(): Boolean = true
        override fun isInfoEnabled(): Boolean = true
        override fun isWarnEnabled(): Boolean = true
        override fun isErrorEnabled(): Boolean = true

        override fun getFullyQualifiedCallerName(): String? = null

        override fun handleNormalizedLoggingCall(
            level: Level,
            marker: Marker?,
            messagePattern: String?,
            arguments: Array<out Any>?,
            throwable: Throwable?,
        ) {
            val message = MessageFormatter.basicArrayFormat(messagePattern, arguments)
            val cause = throwable?.let { " ${it::class.simpleName}: ${it.message}" } ?: ""
            CapturedLogs.lines += "$level $name - $message$cause"
        }
    }
}

package io.whozoss.agentos.plugins.kotlin

import mu.KLogging
import org.pf4j.Plugin

/**
 * PF4J lifecycle entry point of the Kotlin plugin (`Plugin-Class` of the jar manifest).
 *
 * It only logs and releases the language servers and the worker threads: the tools are contributed by
 * [KotlinToolProvider], discovered as a PF4J extension.
 */
class KotlinPlugin : Plugin() {
    /** Called by PF4J when the plugin is started. */
    override fun start() {
        logger.info { "Kotlin Plugin started!" }
    }

    /** Called by PF4J when the plugin is stopped. */
    override fun stop() {
        LspConnectionPool.SHARED.closeAll()
        KotlinLspWorkers.shutdown()
        logger.info { "Kotlin Plugin stopped!" }
    }

    companion object : KLogging()
}

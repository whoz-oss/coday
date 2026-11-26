package io.whozoss.agentos.plugins.ktlint

import mu.KLogging
import org.pf4j.Plugin

/**
 * PF4J lifecycle entry point of the ktlint plugin (`Plugin-Class` of the jar manifest).
 *
 * It only logs and releases the worker threads and the engines: the tools are contributed by [KtlintToolProvider], discovered as a
 * PF4J extension. Nothing has to be rebuilt on [start]: [KtlintWorkers] creates its pool again on the next run.
 */
class KtlintPlugin : Plugin() {
    /** Called by PF4J when the plugin is started. */
    override fun start() {
        logger.info { "ktlint Plugin started!" }
    }

    /** Called by PF4J when the plugin is stopped. */
    override fun stop() {
        KtlintWorkers.shutdown()
        KtlintRunnerCache.clear()
        logger.info { "ktlint Plugin stopped!" }
    }

    companion object : KLogging()
}

package io.whozoss.agentos.plugins.gradle

import mu.KLogging
import org.pf4j.Plugin

class GradlePlugin : Plugin() {
    override fun start() {
        logger.info { "Gradle Plugin started!" }
    }

    override fun stop() {
        logger.info { "Gradle Plugin stopped!" }
    }

    companion object : KLogging()
}

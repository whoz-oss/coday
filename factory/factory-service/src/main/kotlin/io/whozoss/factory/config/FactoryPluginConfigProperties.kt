package io.whozoss.factory.config

import java.nio.file.Path

/**
 * Internal source of the PF4J runtime installation directory.
 *
 * Production resolves `factory.plugins.dir` (env `FACTORY_PLUGINS_DIR`, default
 * `plugins/` relative to the working directory), like AgentOS. Tests replace this
 * bean to keep plugin discovery isolated.
 */
fun interface FactoryPluginsDirectory {
    fun path(): Path
}

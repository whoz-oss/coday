package io.whozoss.factory.config

import java.nio.file.Path

/**
 * Internal source of the PF4J runtime installation directory.
 *
 * Production always resolves to `factory/plugins/`, anchored to the Factory
 * installation root rather than to the process working directory. Tests can
 * replace this bean to keep plugin discovery isolated.
 */
fun interface FactoryPluginsDirectory {
    fun path(): Path
}

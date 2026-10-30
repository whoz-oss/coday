package io.whozoss.agentos.plugins.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator

/**
 * The Jackson mappers shared by the request and response code of the plugin (no DI in a PF4J plugin, so
 * one instance each). The config parser keeps its own mapper: it needs the Kotlin module and strict
 * settings that plain argument and response handling must not inherit.
 */
internal object HttpApiJson {

    val mapper: ObjectMapper = ObjectMapper()

    /** Renders without the `---` document start marker and with as few quotes as YAML allows. */
    val yamlMapper: ObjectMapper = ObjectMapper(
        YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES),
    )
}

package io.whozoss.agentos.plugins.http

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLWriteFeature

/**
 * The Jackson mappers shared by the request and response code of the plugin (no DI in a PF4J plugin, so
 * one instance each). The config parser keeps its own mapper: it needs the Kotlin module and strict
 * settings that plain argument and response handling must not inherit.
 */
internal object HttpApiJson {

    val mapper: ObjectMapper = JsonMapper()

    /** Renders without the `---` document start marker and with as few quotes as YAML allows. */
    val yamlMapper: ObjectMapper = YAMLMapper.builder()
        .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
        .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
        .build()
}

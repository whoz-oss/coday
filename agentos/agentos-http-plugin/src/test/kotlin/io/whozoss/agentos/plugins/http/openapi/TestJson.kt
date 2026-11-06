package io.whozoss.agentos.plugins.http.openapi

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

private val testMapper = jacksonObjectMapper()

fun json(text: String): JsonNode = testMapper.readTree(text)

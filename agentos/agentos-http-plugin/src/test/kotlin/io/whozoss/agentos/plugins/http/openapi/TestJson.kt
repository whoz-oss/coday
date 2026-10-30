package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

private val testMapper = jacksonObjectMapper()

fun json(text: String): JsonNode = testMapper.readTree(text)

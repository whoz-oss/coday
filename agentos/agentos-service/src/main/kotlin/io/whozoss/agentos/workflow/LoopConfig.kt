package io.whozoss.agentos.workflow

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoopConfig(val version: Int = 1, val steps: List<LoopStep> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoopStep(val id: String, val tool: String, val input: LoopStepInput = LoopStepInput())

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoopStepInput(val params: JsonNode? = null, val from: String? = null)

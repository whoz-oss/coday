package io.whozoss.agentos.agent

/**
 * Raw result of [AgentAdvanced.detectToolRepetition]: the name of the most-repeated
 * (toolName, args) pair in the detection window and how many times it appeared.
 *
 * No policy is embedded here — the count is compared against thresholds by
 * [AgentAdvanced.handleRepetition].
 */
data class ToolRepetition(val toolName: String, val count: Int)

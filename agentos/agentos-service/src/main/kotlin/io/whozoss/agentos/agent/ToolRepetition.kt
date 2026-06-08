package io.whozoss.agentos.agent

/**
 * Carries the result of [AgentAdvanced.detectToolRepetition]: the name of the
 * repeated tool and how many times it appeared in the detection window.
 *
 * The caller (the run loop) decides what to do based on this value and the
 * current loop state — no policy is embedded here.
 */
data class ToolRepetition(val toolName: String, val count: Int)

package io.whozoss.agentos.exception

/**
 * Thrown when recursive prompt resolution cannot complete.
 *
 * Covers two distinct failure modes:
 * - A cycle is detected: the same prompt (name + resolved arguments) appears in its own call stack.
 * - The maximum nesting depth is exceeded: a long chain of distinct prompts
 *   prevents termination within the allowed depth.
 *
 */
class PromptResolutionException(message: String) : RuntimeException(message)

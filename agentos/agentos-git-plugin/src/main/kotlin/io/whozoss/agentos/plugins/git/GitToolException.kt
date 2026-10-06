package io.whozoss.agentos.plugins.git

/** A refusal or failure whose message is safe to return to the agent. */
internal class GitToolException(message: String) : RuntimeException(message)

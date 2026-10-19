package io.whozoss.agentos.plugins.file.tools

/**
 * Appends the configured scope guidance, if any, to a tool's own description.
 *
 * The `scopeGuidance` config key is set when the tools being built write into one of several
 * surfaces the model has to choose between, whose descriptions are otherwise identical. AgentOS
 * sets it for the case and namespace file exchanges, and it is published in the integration's
 * config schema so anyone configuring several FILE_ACCESS integrations can do the same. An
 * integration that sets no key keeps its descriptions verbatim.
 *
 * Only the write tools call this. Providers cap a tool description (OpenAI rejects one past 1024
 * characters) and the read tools of this plugin — `readSpreadsheet` above all — are already close
 * enough to that ceiling that appending to them would break a run on those providers.
 */
internal fun String.withScopeGuidance(guidance: String?): String =
    when {
        guidance.isNullOrBlank() -> this
        else -> "$this\n${guidance.trim()}"
    }

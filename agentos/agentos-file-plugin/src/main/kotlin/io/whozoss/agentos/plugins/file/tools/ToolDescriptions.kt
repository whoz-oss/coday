package io.whozoss.agentos.plugins.file.tools

/**
 * Appends the scope guidance the host supplied, if any, to a tool's own description.
 *
 * The host sets the `scopeGuidance` config key when the tools it is building write into one of
 * several surfaces the model has to choose between — the AgentOS case and namespace file exchanges,
 * which produce two tool sets whose descriptions are otherwise identical. A FILE_ACCESS integration
 * configured by a user gets no key and keeps its description verbatim.
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

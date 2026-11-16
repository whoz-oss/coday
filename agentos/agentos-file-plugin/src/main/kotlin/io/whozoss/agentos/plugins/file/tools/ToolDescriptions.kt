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
        else -> "$this\n${guidance.trim().clampedToLength(MAX_SCOPE_GUIDANCE_LENGTH)}"
    }

/**
 * The first [limit] characters, never cut inside a surrogate pair.
 *
 * `take` counts UTF-16 code units, so a limit landing between the two halves of an astral character
 * (any emoji) would leave a lone high surrogate. Jackson refuses to serialise one, which would fail
 * every request of every run of the agent carrying that description, rather than the cosmetic
 * truncation this clamp is for.
 */
private fun String.clampedToLength(limit: Int): String = take(limit).trimEnd { it.isHighSurrogate() }

/**
 * The longest scope guidance appended to a description.
 *
 * The key is user-settable through the FILE_ACCESS config schema, and a description past 1024
 * characters is rejected outright by OpenAI, which would fail every turn of every run of the agent
 * holding that integration. Clamping here rather than refusing the config keeps a too-long sentence
 * a cosmetic problem: `editFiles`, the longest write tool, sits around 325 characters, so this
 * leaves well over half the budget unused.
 */
internal const val MAX_SCOPE_GUIDANCE_LENGTH = 400

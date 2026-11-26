package io.whozoss.agentos.plugins.ktlint

import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider

/**
 * The ONE ktlint rule engine of the plugin, shared by every project and every tool call.
 *
 * The engine is not tied to a directory: its constructor takes only the rule providers (and the optional `.editorconfig`
 * defaults and overrides, filesystem and CLI flag, none of which is used here). The `.editorconfig` files are looked up
 * by the engine for each file it processes, upwards from the location of that file, so the files of different projects
 * get the style of their own project. It keeps no state of a run: the rules are instantiated per call and the
 * `.editorconfig` cache it fills is thread-safe.
 *
 * Known limit: that internal cache grows with the number of distinct directories the engine has met since it was
 * created (ktlint has no eviction). It is emptied and the engine dropped by [reset], called when the plugin stops.
 *
 * The engine is created on first use and created again after [reset]: PF4J keeps the classloader (hence this object)
 * when it stops then starts the plugin again.
 */
internal object KtlintEngine {
    private val lock = Any()

    private var current: KtLintRuleEngine? = null

    /** The shared engine: the one in use, or a new one when none was created yet or after a [reset]. */
    val shared: KtLintRuleEngine
        get() = synchronized(lock) { current ?: create().also { current = it } }

    /** Empties the `.editorconfig` cache of ktlint and drops the engine; the next [shared] is a new one. */
    fun reset() {
        synchronized(lock) {
            current?.trimMemory()
            current = null
        }
    }

    private fun create(): KtLintRuleEngine = KtLintRuleEngine(ruleProviders = StandardRuleSetProvider().getRuleProviders())
}

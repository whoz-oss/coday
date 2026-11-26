package io.whozoss.agentos.plugins.ktlint

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * One [KtlintRunner] (hence one ktlint rule engine) per project, built on first use and reused by every tool call on
 * that project, instead of one engine per `provideTools`, that is potentially per agent run.
 *
 * The engine is safe to share between threads: it keeps no state of a run (rules are instantiated per call by their
 * providers, the `.editorconfig` cache is a thread-safe one).
 */
internal object KtlintRunnerCache {
    private val runners = ConcurrentHashMap<Path, KtlintRunner>()

    /** The runner of the project at [projectRoot], keyed by its real path. */
    fun runnerFor(projectRoot: Path): KtlintRunner {
        val realRoot = projectRoot.toRealPath()
        return runners.computeIfAbsent(realRoot) { KtlintRunner(projectRoot = it) }
    }

    /** Forgets every runner; called when the plugin stops. */
    fun clear() {
        runners.clear()
    }
}

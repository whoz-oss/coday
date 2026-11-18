package io.whozoss.agentos.plugins.gradle

/**
 * The JVM restricted-method warnings, printed when a native library calls a restricted API (e.g. `System::load`):
 *   WARNING: A restricted method in java.lang.System has been called
 *   WARNING: java.lang.System::load has been called by ...
 *   WARNING: Use --enable-native-access=ALL-UNNAMED ...
 *   WARNING: Restricted methods will be blocked ...
 * Any other `WARNING:` line (Gradle, a plugin, a tool) is a real warning and is kept.
 */
private val JVM_NOISE =
    Regex(
        """^WARNING: (?:A restricted method in .*|\S+::\S+ has been called by .*|Use --enable-native-access.*|Restricted methods will be blocked.*)$""",
    )

private val BLANK_RUNS = Regex("\n{3,}")

/**
 * Strips the JVM restricted-method warnings ([JVM_NOISE]) from Gradle output: they pollute tool output without
 * adding diagnostic value. Runs of 3+ blank lines left by the removal are collapsed to 2.
 */
internal fun String.filterJvmNoise(): String =
    lines()
        .filterNot { JVM_NOISE.matches(it) }
        .joinToString("\n")
        .replace(BLANK_RUNS, "\n\n")
        .trim()

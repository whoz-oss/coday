package io.whozoss.agentos.plugins.gradle

/**
 * Strips JVM restricted-method warnings from Gradle output.
 *
 * These warnings are emitted by the JVM when native libraries call restricted APIs
 * (e.g. `System::load`). They pollute tool output without adding diagnostic value.
 *
 * Removed pattern:
 *   WARNING: A restricted method in java.lang.System has been called
 *   WARNING: java.lang.System::load has been called by ...
 *   WARNING: Use --enable-native-access=ALL-UNNAMED ...
 *   WARNING: Restricted methods will be blocked ...
 */
internal fun String.filterJvmNoise(): String {
    val lines = lines()
    val filtered = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("WARNING:")) {
            i++
            continue
        }
        filtered += line
        i++
    }
    // Collapse runs of 3+ blank lines down to 2 (avoids large gaps left by removed WARNING blocks)
    return filtered
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

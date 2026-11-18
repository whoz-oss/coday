package io.whozoss.factory.forge.bmad

/**
 * Extracts the deployed release and target from a successful Whoz Deploy job's
 * own log. The caller must first verify the GitHub job identity and successful
 * conclusion; this parser never treats arbitrary text as a deployment result.
 */
internal object ForgeDeploymentLogReceipt {
    data class Receipt(val tag: String, val target: String)

    fun parse(jobName: String, conclusion: String, log: String): Receipt? {
        if (jobName != "deploy to workstream" || conclusion != "success") return null
        if (log.toByteArray(Charsets.UTF_8).size > MAX_LOG_BYTES) return null
        val tags = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        for (line in log.lineSequence()) {
            val clean = ANSI.replace(line, "")
            TAG.find(clean)?.groupValues?.get(1)?.let(tags::add)
            TARGET.find(clean)?.groupValues?.get(1)?.let(targets::add)
        }
        if (tags.size != 1 || targets.size != 1) return null
        return Receipt(tags.single(), targets.single())
    }

    private val ANSI = Regex("\\u001B\\[[0-9;]*[A-Za-z]")
    private val TAG = Regex("(?:^|\\s)RELEASE_TAG:\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,127})(?:\\s|$)")
    private val TARGET = Regex("(?:^|\\s)TARGET_ENVIRONMENT:\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,127})(?:\\s|$)")
    private const val MAX_LOG_BYTES = 8 * 1024 * 1024
}

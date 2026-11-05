package io.whozoss.agentos.transcription

/** The audio types accepted for voice messages, and the file extension each one is stored with. */
internal object AudioMimeTypes {
    /** Types a voice message upload may carry, compared on the base type (parameters such as `;codecs=opus` dropped). */
    val ALLOWED: Set<String> =
        setOf(
            "audio/webm",
            "audio/ogg",
            "audio/mp4",
            "audio/mpeg",
            "audio/mp3",
            "audio/wav",
            "audio/x-wav",
            "audio/m4a",
            "audio/x-m4a",
        )

    private val EXTENSIONS: Map<String, String> =
        mapOf(
            "audio/webm" to "webm",
            "audio/ogg" to "ogg",
            "audio/mp4" to "m4a",
            "audio/m4a" to "m4a",
            "audio/x-m4a" to "m4a",
            "audio/mpeg" to "mp3",
            "audio/mp3" to "mp3",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/wave" to "wav",
            "audio/flac" to "flac",
        )

    /** `audio/webm;codecs=opus` -> `audio/webm`, lowercased; null for a null or blank value. */
    fun baseType(mimeType: String?): String? =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    fun isAllowed(mimeType: String?): Boolean = baseType(mimeType) in ALLOWED

    /** The extension for [mimeType], or null when the type is not a known audio type. */
    fun extensionFor(mimeType: String?): String? = baseType(mimeType)?.let { EXTENSIONS[it] }
}

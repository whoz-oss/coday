package io.whozoss.agentos.git.core

/** Object ids in the two formats Git supports. */
object GitObjectIds {
    const val SHA1_LENGTH: Int = 40

    const val SHA256_LENGTH: Int = 64

    const val SHA1_FORMAT: String = "sha1"

    const val SHA256_FORMAT: String = "sha256"

    /** A full object id in either format. */
    val FULL_ID: Regex = Regex("[a-f0-9]{$SHA1_LENGTH}|[a-f0-9]{$SHA256_LENGTH}")

    fun length(format: String): Int = if (format == SHA256_FORMAT) SHA256_LENGTH else SHA1_LENGTH

    /** The all-zero id Git uses for "no object", in [format]. */
    fun zero(format: String): String = "0".repeat(length(format))

    fun pattern(format: String): Regex = Regex("[a-f0-9]{${length(format)}}")
}

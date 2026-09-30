package io.whozoss.agentos.git.core

/** Conventions of Git's machine-readable output and of its no-file placeholders. */
object GitOutputFormat {
    /** Separator of `-z` output: NUL is the only byte a path cannot contain. */
    const val NUL: Char = '\u0000'

    /** Stands for "no file": an empty side of a diff, or a disabled configuration file. */
    const val NULL_DEVICE: String = "/dev/null"
}

package io.whozoss.agentos.claudeCode

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText

/**
 * A shell script standing in for the `claude` executable, so tests exercise real processes without the
 * network or a Claude login. [body] runs after the script has recorded its arguments, environment and
 * working directory into [directory].
 */
class FakeClaudeCli(
    val directory: Path,
    body: String,
) {
    val executable: Path = directory.resolve("claude")

    init {
        executable.writeText(
            """
            |#!/bin/sh
            |printf '%s\n' "${'$'}@" > '$directory/argv'
            |env > '$directory/env'
            |pwd > '$directory/cwd'
            |$body
            """.trimMargin() + "\n",
        )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    }

    fun arguments(): List<String> = Files.readAllLines(directory.resolve("argv"))

    fun environment(): Map<String, String> =
        Files
            .readAllLines(directory.resolve("env"))
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    fun workingDirectory(): String = Files.readString(directory.resolve("cwd")).trim()

    companion object {
        fun fixture(name: String): Path = Path.of(FakeClaudeCli::class.java.getResource("/claudeCode/$name")!!.toURI())
    }
}

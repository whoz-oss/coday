package io.whozoss.agentos.plugins.gradle

import io.kotest.matchers.types.shouldBeInstanceOf

/** Asserts the result is a [GradleToolResult.Success] and gives its text. */
internal fun GradleToolResult.successText(): String = shouldBeInstanceOf<GradleToolResult.Success>().text

/** Asserts the result is a [GradleToolResult.Failure] (Gradle ran and failed) and gives its text. */
internal fun GradleToolResult.failureText(): String = shouldBeInstanceOf<GradleToolResult.Failure>().text

/** Asserts the result is a [GradleToolResult.Error] (the tool could not do its job) and gives its text. */
internal fun GradleToolResult.errorText(): String = shouldBeInstanceOf<GradleToolResult.Error>().text

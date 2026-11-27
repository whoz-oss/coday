package io.whozoss.agentos.plugins.ktlint

import io.kotest.matchers.types.shouldBeInstanceOf

/** Asserts the result is a [KtlintToolResult.Success] and gives its text. */
internal fun KtlintToolResult.successText(): String = shouldBeInstanceOf<KtlintToolResult.Success>().text

/** Asserts the result is a [KtlintToolResult.Failure] (ktlint ran and violations remain) and gives its text. */
internal fun KtlintToolResult.failureText(): String = shouldBeInstanceOf<KtlintToolResult.Failure>().text

/** Asserts the result is a [KtlintToolResult.Error] (the tool could not do its job) and gives its text. */
internal fun KtlintToolResult.errorText(): String = shouldBeInstanceOf<KtlintToolResult.Error>().text

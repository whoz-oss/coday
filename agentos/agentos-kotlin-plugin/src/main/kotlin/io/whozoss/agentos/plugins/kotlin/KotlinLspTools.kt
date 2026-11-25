package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.sdk.tool.StandardTool

/**
 * The 18 tools of one KOTLIN integration, all working on the Kotlin project of [run].
 *
 * @param configName name of the integration config, prefixing every tool name when there is one
 */
internal fun kotlinLspTools(
    configName: String?,
    run: KotlinLspRun,
): List<StandardTool<*>> =
    navigationTools(configName = configName, run = run) +
        refactoringTools(configName = configName, run = run) +
        searchTools(configName = configName, run = run) +
        editorTools(configName = configName, run = run)

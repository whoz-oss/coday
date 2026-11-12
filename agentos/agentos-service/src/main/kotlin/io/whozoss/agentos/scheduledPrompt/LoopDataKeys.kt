package io.whozoss.agentos.scheduledPrompt

/**
 * The `loopData` a loop-mode [ScheduledPrompt] hands to its LOOP agent: the keys the scheduler writes in the
 * session context entry [io.whozoss.agentos.caseFlow.SessionContextKeys.LOOP_DATA] of the loop case's first
 * message (through [io.whozoss.agentos.workflow.LoopDataSessionContext]).
 *
 * This is the scheduler's contract with the `loopConfig` of the loop agents: their steps reference these keys
 * as `${loopData.<key>}` placeholders. Renaming one breaks every loop config that reads it.
 */
object LoopDataKeys {
    /** Exact name of the search tool, as granted to the loop agent ([LoopConfiguration.searchToolName]). */
    const val SEARCH_TOOL_NAME = "searchToolName"

    /** Input of the search tool, a JSON object ([LoopConfiguration.searchToolArgs]); `{}` when none. */
    const val SEARCH_TOOL_ARGS = "searchToolArgs"

    /** Name of the agent started for each search hit ([ScheduledPrompt.agentConfigId]). */
    const val AGENT_NAME = "agentName"

    /** Prompt sent to that agent: the content of the scheduled prompt's template, neither translated nor enriched. */
    const val PROMPT = "prompt"

    /** Id of the [ScheduledPrompt], to link each started case to it. */
    const val SCHEDULED_PROMPT_ID = "scheduledPromptId"
}

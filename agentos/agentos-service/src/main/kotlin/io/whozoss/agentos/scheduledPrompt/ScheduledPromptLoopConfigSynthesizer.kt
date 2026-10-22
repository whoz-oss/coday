package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service

/**
 * Synthesizes a default [io.whozoss.agentos.workflow.LoopConfig] for a [ScheduledPrompt]
 * that has no explicit [ScheduledPrompt.loopConfig].
 *
 * The synthesized config is the canonical two-step fan-out:
 * 1. **list-audience** — `ListAgentUsers` tool resolves the audience for the target agent.
 * 2. **fan-out** — `BatchCaseStarter` creates one Case per audience member.
 *
 * The call site is always branch-free:
 * ```kotlin
 * val effective = sp.loopConfig ?: synthesizer.synthesize(sp, agentName, promptContent, title)
 * ```
 *
 * **TRAP**: `sp.agentConfigId` is the TARGET agent each end-user talks to. Never read
 * `AgentConfig.loopConfig` from it — that field belongs to the target agent's own loop
 * config and has nothing to do with the scheduler's fan-out.
 */
@Service
class ScheduledPromptLoopConfigSynthesizer {

    /**
     * Synthesize a default two-step loop config for [scheduledPrompt].
     *
     * @param scheduledPrompt the scheduled prompt being executed
     * @param agentName the name of the target agent (resolved from [ScheduledPrompt.agentConfigId])
     * @param promptContent the resolved prompt content (source language, before per-user translation)
     * @param title the case title
     * @param sourceLanguage the BCP-47 source language of the prompt (from the linked Prompt template)
     */
    fun synthesize(
        scheduledPrompt: ScheduledPrompt,
        agentName: String,
        promptContent: String,
        title: String,
        sourceLanguage: String = "en",
    ): JsonNode {
        // Build the two-step linear LoopConfig matching workflow/LoopConfig.kt:
        // LoopStep(id, tool, input=LoopStepInput(params, from))
        // params is nested INSIDE input.
        val config = mapOf(
            "version" to 1,
            "steps" to listOf(
                mapOf(
                    "id" to "list-audience",
                    "tool" to "ListAgentUsers",
                    "input" to mapOf(
                        "params" to mapOf(
                            "agentConfigId" to scheduledPrompt.agentConfigId.toString(),
                        ),
                    ),
                ),
                mapOf(
                    "id" to "fan-out",
                    "tool" to "BatchCaseStarter",
                    "input" to mapOf(
                        "from" to "list-audience",
                        "params" to mapOf(
                            "agentName" to agentName,
                            "prompt" to promptContent,
                            "title" to title,
                            "translateFrom" to sourceLanguage,
                            "scheduledPromptId" to scheduledPrompt.id.toString(),
                        ),
                    ),
                ),
            ),
        )
        return MAPPER.valueToTree(config)
    }

    companion object {
        private val MAPPER = jacksonObjectMapper()
    }
}

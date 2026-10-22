package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.prompt.PromptService
import io.whozoss.agentos.workflow.LoopConfig
import io.whozoss.agentos.workflow.LoopConfigResolver
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * [LoopConfigResolver] implementation for the scheduler path.
 *
 * Lookup chain:
 * 1. Find the [ScheduledPromptRun] whose [ScheduledPromptRun.loopCaseId] matches [caseId].
 *    Returns null (not our case) if no run is found.
 * 2. Find the parent [ScheduledPrompt] by its id on the run.
 * 3. If the prompt carries an explicit [ScheduledPrompt.loopConfig], deserialise and return it.
 * 4. Otherwise synthesise a default config via [ScheduledPromptLoopConfigSynthesizer], using
 *    the agent name and prompt content resolved from their respective services.
 *
 * [CaseService] is injected lazily to break the bean-creation cycle:
 * CaseService → AgentService → AgentLoop → LoopConfigResolver → CaseService.
 */
@Service
@ConditionalOnProperty(name = ["agentos.prompt.scheduler.enabled"], havingValue = "true")
class ScheduledPromptLoopConfigResolver(
    private val runRepository: ScheduledPromptRunRepository,
    private val scheduledPromptRepository: ScheduledPromptRepository,
    private val agentConfigService: AgentConfigService,
    private val promptService: PromptService,
    private val synthesizer: ScheduledPromptLoopConfigSynthesizer,
    private val objectMapper: ObjectMapper,
) : LoopConfigResolver {
    override fun resolve(caseId: UUID): LoopConfig? {
        val run = runRepository.findByLoopCaseId(caseId) ?: return null // not ours
        val sp =
            scheduledPromptRepository.findByIds(listOf(run.scheduledPromptId)).firstOrNull()
                ?: return null
        val node = sp.loopConfig ?: synthesizeFor(sp, run)
        return objectMapper.treeToValue(node, LoopConfig::class.java)
    }

    private fun synthesizeFor(
        scheduledPrompt: ScheduledPrompt,
        run: ScheduledPromptRun,
    ): com.fasterxml.jackson.databind.JsonNode {
        val agentName =
            agentConfigService.findById(scheduledPrompt.agentConfigId)?.name
                ?: throw IllegalStateException(
                    "AgentConfig ${scheduledPrompt.agentConfigId} not found while resolving loop config for run=${run.id}",
                )
        val prompt =
            promptService.findById(scheduledPrompt.promptTemplateId)
                ?: throw IllegalStateException(
                    "PromptTemplate ${scheduledPrompt.promptTemplateId} not found while resolving loop config for run=${run.id}",
                )
        val promptContent =
            prompt.content.firstOrNull { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "PromptTemplate ${scheduledPrompt.promptTemplateId} has empty content for run=${run.id}",
                )
        return synthesizer.synthesize(
            scheduledPrompt = scheduledPrompt,
            agentName = agentName,
            promptContent = promptContent,
            title = scheduledPrompt.name,
            runId = run.id,
            sourceLanguage = prompt.sourceLanguage,
        )
    }
}

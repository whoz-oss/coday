import { CaseEvent, ToolResponseEvent } from '@whoz-oss/agentos-api-client'

/**
 * The marker every configuration plan carries in its `structuredOutput`. Mirrors
 * `ConfigAgentIntegration.PLAN_SCHEMA`, pinned by `ConfigAgentIntegrationUnitSpec`.
 */
export const CONFIG_CHANGE_PLAN_SCHEMA = 'agentos.config-change/1'

/**
 * Ids of the questions that announce a configuration plan: the plan rides on the propose call's
 * ToolResponseEvent, and its question is answered through the configuration card, not the question
 * panel. A plan is recognised by its schema marker, not by the name of the tool that produced it.
 */
export function configChangeQuestionIds(events: readonly CaseEvent[]): Set<string> {
  const ids = new Set<string>()
  for (const event of events) {
    const plan: unknown = event.type === 'ToolResponseEvent' ? (event as ToolResponseEvent).structuredOutput : null
    if (isPlan(plan)) ids.add(plan.questionId)
  }
  return ids
}

function isPlan(value: unknown): value is { schema: string; questionId: string } {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return candidate['schema'] === CONFIG_CHANGE_PLAN_SCHEMA && typeof candidate['questionId'] === 'string'
}

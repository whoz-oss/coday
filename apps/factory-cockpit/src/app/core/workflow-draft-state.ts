import { FullWorkflowDefinition } from './factory-api.service'

/** Key of the router navigation state that carries a workflow draft to the composer. */
export const WORKFLOW_DRAFT_STATE_KEY = 'workflowDraft'

/** Draft of the definition a run followed, as sent by the session page to `/workflows/new`. */
export interface WorkflowDraftNavigationState {
  workflowId: string
  namespaceId: string
  definition: FullWorkflowDefinition
}

/**
 * `NavigationExtras.state` for opening the composer on a draft. The draft lives in the navigation entry only: a
 * cancelled navigation, or any later visit to `/workflows/new` without this state, starts from a blank workflow.
 */
export function workflowDraftNavigationState(draft: WorkflowDraftNavigationState): Record<string, unknown> {
  return { [WORKFLOW_DRAFT_STATE_KEY]: draft }
}

/**
 * Read the draft definition out of a navigation state (`NavigationExtras.state` or `history.state`).
 * Anything that is not a draft with a `definition.steps` array is ignored, so the composer starts blank.
 */
export function readWorkflowDraft(state: unknown): FullWorkflowDefinition | undefined {
  if (typeof state !== 'object' || state === null) return undefined
  const draft = (state as Record<string, unknown>)[WORKFLOW_DRAFT_STATE_KEY]
  if (typeof draft !== 'object' || draft === null) return undefined
  const definition = (draft as Record<string, unknown>)['definition']
  if (typeof definition !== 'object' || definition === null || Array.isArray(definition)) return undefined
  return Array.isArray((definition as Record<string, unknown>)['steps'])
    ? (definition as FullWorkflowDefinition)
    : undefined
}

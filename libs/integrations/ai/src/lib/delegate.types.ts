/**
 * Shared type for a single delegation request.
 * Used by both delegate.function.ts and delegate.tools.ts to avoid duplication.
 */
export type Delegation = {
  agentName: string
  task: string
  /** Optional: resume an existing thread instead of forking a new one */
  threadId?: string
  /** Optional: fire-and-forget, returns immediately with the threadId without waiting for the agent to finish */
  async?: boolean
}

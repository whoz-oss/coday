/**
 * CaseDefinition — hand-written model for the Agentic Scheduler feature.
 *
 * Matches the backend CaseDefinitionDto at /api/case-definitions.
 * agentConfigId references an existing AgentConfig entity.
 * promptContent holds the opening message sent to the agent when the case fires;
 * the backend manages the lifecycle of the underlying Prompt entity transparently.
 *
 * Scope is determined by (namespaceId, userId):
 *   (null, null)   → platform
 *   (ns,   null)   → namespace-shared
 *   (null, user)   → user-global
 *   (ns,   user)   → user × namespace
 *
 * This lives in src/custom/ to survive OpenAPI client regeneration.
 */
export type CaseDefinitionFrequency = 'DAILY' | 'WEEKLY'

export interface CaseDefinition {
  id?: string
  namespaceId?: string | null
  /** userId — reserved for per-user scope. Usually null for namespace/platform scopes. */
  userId?: string | null
  /** Reference to an AgentConfig entity. Always required. */
  agentConfigId: string
  /** Opening message sent to the agent when the scheduled case fires. The backend manages the underlying Prompt entity transparently. */
  promptContent: string
  /** Slug name: ^[a-z][a-z0-9]*(-[a-z0-9]+)*$ — validated on create, not on update. */
  name: string
  description?: string
  /** Recurrence frequency. */
  frequency: CaseDefinitionFrequency
  /** Time of day in UTC, format HH:mm (e.g. "09:30") */
  timeUtc: string
  /** Day of week for WEEKLY frequency (e.g. 'MON', 'TUE', …, 'SUN'). Required when frequency is WEEKLY. */
  dayOfWeek?: string | null
  enabled: boolean
  /**
   * Read-only server timestamps — present in GET responses, never sent in POST/PUT payloads.
   * Declared readonly to prevent accidental inclusion in request bodies.
   */
  readonly createdAt?: string
  readonly updatedAt?: string
}

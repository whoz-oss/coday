/**
 * CaseDefinition — hand-written model for the Agentic Scheduler feature.
 *
 * Matches the backend CaseDefinition entity at
 * GET/POST/PUT /api/case-definitions
 *
 * This lives in src/custom/ to survive OpenAPI client regeneration.
 */
export type CaseDefinitionFrequency = 'DAILY' | 'WEEKLY'

export interface CaseDefinition {
  id?: string
  namespaceId?: string
  /** userId — reserved for future per-user scope, not exposed in UI yet. */
  userId?: string
  agentId: string
  name: string
  description?: string
  prompt: string
  frequency: CaseDefinitionFrequency
  /** Time of day in UTC, format HH:mm (e.g. "09:30") */
  timeUtc: string
  /** Day of week for WEEKLY frequency (e.g. 'MON', 'TUE', …, 'SUN'). Required when frequency is WEEKLY. */
  dayOfWeek?: string
  enabled: boolean
  createdAt?: string
  updatedAt?: string
}

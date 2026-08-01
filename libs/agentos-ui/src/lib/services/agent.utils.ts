import { AgentConfig } from '@whoz-oss/agentos-api-client'

/**
 * Filters agents by prefix against name and description, sorted with name matches first.
 *
 * Ordering:
 * 1. Agents whose **name** starts with the prefix (sorted alphabetically)
 * 2. Agents whose **description** contains the prefix but name doesn't match (sorted alphabetically)
 *
 * @param agents  The full list of effective agents.
 * @param prefix  The user-typed prefix after `@`.
 */
export function filterAndSortAgents(agents: AgentConfig[], prefix: string): AgentConfig[] {
  const lower = prefix.toLowerCase()
  const nameMatches: AgentConfig[] = []
  const descMatches: AgentConfig[] = []

  for (const a of agents) {
    if (a.name.toLowerCase().startsWith(lower)) {
      nameMatches.push(a)
    } else if (a.description?.toLowerCase().includes(lower)) {
      descMatches.push(a)
    }
  }

  return [...nameMatches, ...descMatches]
}

import { UserGroupSearchResult } from '@whoz-oss/agentos-api-client'

/**
 * Maps each agentId deployed to a group OTHER than [currentGroupId] to that group's name.
 *
 * Used by the form to disable agents already deployed elsewhere in the namespace — an agent may
 * be deployed to at most one group per namespace. When editing, the current group's own agents
 * are excluded (via [currentGroupId]) so they remain selectable.
 */
export function computeTakenElsewhere(
  groups: UserGroupSearchResult[],
  currentGroupId: string | null
): Map<string, string> {
  const taken = new Map<string, string>()
  for (const group of groups) {
    if (group.userGroupId === currentGroupId) continue
    for (const agentId of group.agentIds) {
      if (!taken.has(agentId)) taken.set(agentId, group.name)
    }
  }
  return taken
}

/**
 * Diffs the original member set against the selected one, producing the add/remove lists an
 * update request expects.
 */
export function computeMemberDiff(original: string[], selected: string[]): { toAdd: string[]; toRemove: string[] } {
  const originalSet = new Set(original)
  const selectedSet = new Set(selected)
  return {
    toAdd: selected.filter((id) => !originalSet.has(id)),
    toRemove: original.filter((id) => !selectedSet.has(id)),
  }
}

/** Best display label for a user: "First Last" when available, else the external id. */
export function memberLabel(user: { firstname?: string; lastname?: string; externalId: string }): string {
  const name = [user.firstname, user.lastname].filter(Boolean).join(' ').trim()
  return name || user.externalId
}

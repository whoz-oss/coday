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

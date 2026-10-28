/**
 * Filter and sort autocomplete items (`@` agents, `/` commands/prompts) by query relevance,
 * prioritizing matches on `name` before matches on `description`.
 *
 * - When `query` is empty: returns all items sorted alphabetically by `name` (case-insensitive).
 * - When `query` is provided: filters items matching `name` or `description` (case-insensitive)
 *   and orders them by relevance tier, breaking ties alphabetically by `name`:
 *     0. `name` starts with `query` (highest relevance)
 *     1. `name` contains `query`
 *     2. `description` contains `query` only (lowest relevance)
 *
 * @param items - Full list of items to filter and sort (not mutated)
 * @param query - User-typed query string after the trigger (`@` or `/`)
 * @returns Filtered and sorted array of items
 */
export function filterAndSortAutocomplete<T extends { name: string; description?: string }>(
  items: readonly T[],
  query: string
): T[] {
  const compareByName = (a: string, b: string): number => a.localeCompare(b)

  if (!query) {
    return items
      .map((item) => ({ item, lowerName: item.name.toLowerCase() }))
      .sort((a, b) => compareByName(a.lowerName, b.lowerName))
      .map(({ item }) => item)
  }

  const lowerQuery = query.toLowerCase()
  const matched: Array<{ item: T; tier: 0 | 1 | 2; lowerName: string }> = []

  for (const item of items) {
    const lowerName = item.name.toLowerCase()
    if (lowerName.startsWith(lowerQuery)) {
      matched.push({ item, tier: 0, lowerName })
    } else if (lowerName.includes(lowerQuery)) {
      matched.push({ item, tier: 1, lowerName })
    } else if (item.description?.toLowerCase().includes(lowerQuery)) {
      matched.push({ item, tier: 2, lowerName })
    }
  }

  return matched.sort((a, b) => a.tier - b.tier || compareByName(a.lowerName, b.lowerName)).map(({ item }) => item)
}

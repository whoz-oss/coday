import { filterAndSortAutocomplete } from './autocomplete.utils'

interface TestItem {
  name: string
  description?: string
}

const item = (name: string, description?: string): TestItem => ({ name, description })

describe('filterAndSortAutocomplete', () => {
  describe('empty query', () => {
    it('returns all items sorted alphabetically by name (case-insensitive) without mutating input', () => {
      const input = [item('re-prompt'), item('Delegate'), item('plan'), item('chore-bump')]
      const copy = [...input]

      const result = filterAndSortAutocomplete(input, '')

      expect(result.map((i) => i.name)).toEqual(['chore-bump', 'Delegate', 'plan', 're-prompt'])
      expect(input).toEqual(copy)
    })

    it('returns an empty array when input is empty', () => {
      expect(filterAndSortAutocomplete([], '')).toEqual([])
    })
  })

  describe('filtering and relevance ordering (name prioritized over description)', () => {
    it('orders tier 0 (name startsWith) > tier 1 (name includes) > tier 2 (description includes)', () => {
      const descOnly = item('pe-stale-tickets', 'Reports tickets in Ready, In Progress, or Review for >5 days.')
      const nameContains1 = item('wz-pr-review', 'Standard Whoz PR review')
      const nameContains2 = item('pr-review', 'Ask Reviewer agent for a review of the PR')
      const nameStarts = item('review-code', 'Review local changes')
      const unrelated = item('other', 'Something else')

      const result = filterAndSortAutocomplete(
        [descOnly, nameContains1, unrelated, nameContains2, nameStarts],
        'review'
      )

      expect(result).toEqual([nameStarts, nameContains2, nameContains1, descOnly])
    })

    it('sorts alphabetically by name within the same relevance tier', () => {
      const items = [
        item('re-prompt', 'Transform request'),
        item('regen-api-client', 'Regenerate API client'),
        item('random-slack', 'Send re message'),
        item('chore-re-run', 'Run again'),
        item('auto-re-check', 'Check again'),
      ]

      const result = filterAndSortAutocomplete(items, 're')

      expect(result.map((i) => i.name)).toEqual([
        // Tier 0: startsWith('re') sorted alphabetically
        're-prompt',
        'regen-api-client',
        // Tier 1: includes('re') sorted alphabetically
        'auto-re-check',
        'chore-re-run',
        // Tier 2: description includes('re')
        'random-slack',
      ])
    })

    it('matches case-insensitively on query, name, and description', () => {
      const items = [item('PlanBuilder', 'builds'), item('other', 'Uses PLAN mode')]

      expect(filterAndSortAutocomplete(items, 'PLAN').map((i) => i.name)).toEqual(['PlanBuilder', 'other'])
      expect(filterAndSortAutocomplete(items, 'plan').map((i) => i.name)).toEqual(['PlanBuilder', 'other'])
    })

    it('handles missing or undefined description safely', () => {
      const items = [item('plan'), item('other')]

      expect(() => filterAndSortAutocomplete(items, 'plan')).not.toThrow()
      expect(filterAndSortAutocomplete(items, 'plan')).toEqual([item('plan')])
    })

    it('returns an empty array when nothing matches', () => {
      const items = [item('plan', 'Build a plan'), item('delegate', 'Delegate a task')]

      expect(filterAndSortAutocomplete(items, 'zzz')).toEqual([])
    })
  })
})

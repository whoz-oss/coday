import { Case, CaseStatusEnum } from '@whoz-oss/agentos-api-client'
import {
  CaseAgentGroup,
  caseRecencyKey,
  filterCasesByQuery,
  groupCasesByAgent,
  knownAgentNamesFrom,
  normalizeAgentName,
  OTHERS_GROUP_KEY,
  OTHERS_GROUP_LABEL,
} from './case-agent-grouping.utils'

const makeCase = (overrides: Partial<Case> = {}): Case => ({
  id: 'c',
  namespaceId: 'ns',
  favorite: false,
  removed: false,
  status: CaseStatusEnum.IDLE,
  ...overrides,
})

const ids = (cases: readonly Case[]): (string | undefined)[] => cases.map((c) => c.id)
const KNOWN = ['Dev', 'Reviewer', 'Writer']

/** The group at [index], failing loudly when absent (keeps the assertions free of `undefined`). */
const groupAt = (groups: CaseAgentGroup[], index: number): CaseAgentGroup => {
  const group = groups[index]
  if (!group) {
    throw new Error(`No group at index ${index}`)
  }
  return group
}

describe('normalizeAgentName', () => {
  it.each([
    ['Dev', 'dev'],
    ['  Dev  ', 'dev'],
    ['', ''],
    [undefined, ''],
    [null, ''],
  ])('normalizes %p to %p', (input, expected) => {
    expect(normalizeAgentName(input)).toBe(expected)
  })
})

describe('caseRecencyKey', () => {
  it('uses modified first, then created, then an empty key', () => {
    expect(caseRecencyKey(makeCase({ modified: 'm', created: 'c' }))).toBe('m')
    expect(caseRecencyKey(makeCase({ created: 'c' }))).toBe('c')
    expect(caseRecencyKey(makeCase())).toBe('')
  })
})

describe('knownAgentNamesFrom', () => {
  it('returns the names of the agents', () => {
    expect(knownAgentNamesFrom([{ name: 'Dev' }, { name: 'Writer' }])).toEqual(['Dev', 'Writer'])
  })

  it.each([[[]], [[{ name: '' }, { name: '  ' }]]])('reads %j as unknown (null), not as "no agent"', (agents) => {
    expect(knownAgentNamesFrom(agents)).toBeNull()
  })
})

describe('filterCasesByQuery', () => {
  const cases = [makeCase({ id: 'abc', title: 'Fix login' }), makeCase({ id: 'xyz', title: 'Write docs' })]

  it('returns every case for an empty query', () => {
    expect(ids(filterCasesByQuery(cases, '  '))).toEqual(['abc', 'xyz'])
  })

  it.each([
    ['LOGIN', ['abc']],
    ['xy', ['xyz']],
    ['nothing', []],
  ])('matches title or id case-insensitively: %s', (query, expected) => {
    expect(ids(filterCasesByQuery(cases, query))).toEqual(expected)
  })
})

describe('groupCasesByAgent', () => {
  describe('when cases carry an origin agent', () => {
    it('groups each case under its origin agent only', () => {
      const groups = groupCasesByAgent(
        [
          makeCase({ id: 'a', originAgentName: 'Dev', modified: '2026-01-02' }),
          makeCase({ id: 'b', originAgentName: 'Reviewer', modified: '2026-01-03' }),
          makeCase({ id: 'c', originAgentName: 'Dev', modified: '2026-01-01' }),
        ],
        { knownAgentNames: KNOWN }
      )

      expect(groups.map((g) => [g.label, ids(g.cases)])).toEqual([
        ['Reviewer', ['b']],
        ['Dev', ['a', 'c']],
      ])
    })

    it('matches the origin agent case-insensitively and shows the name the namespace knows', () => {
      const groups = groupCasesByAgent(
        [makeCase({ id: 'a', originAgentName: 'dev' }), makeCase({ id: 'b', originAgentName: ' DEV ' })],
        { knownAgentNames: KNOWN }
      )

      expect(groups).toHaveLength(1)
      expect(groupAt(groups, 0)).toMatchObject({ key: 'agent:dev', label: 'Dev', isOthers: false })
      expect(ids(groupAt(groups, 0).cases).sort()).toEqual(['a', 'b'])
    })
  })

  describe('when a case is a sub-case', () => {
    it('excludes every case with a parentCaseId, even when its origin agent is known', () => {
      const groups = groupCasesByAgent(
        [
          makeCase({ id: 'root', originAgentName: 'Dev' }),
          makeCase({ id: 'child', originAgentName: 'Reviewer', parentCaseId: 'root' }),
          makeCase({ id: 'orphan', originAgentName: 'Writer', parentCaseId: 'not-in-the-list' }),
        ],
        { knownAgentNames: KNOWN }
      )

      expect(groups.flatMap((g) => ids(g.cases))).toEqual(['root'])
    })
  })

  describe('when the origin agent is missing or unknown', () => {
    it.each([[undefined], [''], ['   ']])('puts a case with origin %p in Others', (originAgentName) => {
      const groups = groupCasesByAgent([makeCase({ id: 'a', originAgentName })], { knownAgentNames: KNOWN })

      expect(groups).toHaveLength(1)
      expect(groupAt(groups, 0)).toMatchObject({ key: OTHERS_GROUP_KEY, label: OTHERS_GROUP_LABEL, isOthers: true })
    })

    it('puts a case whose agent was deleted or renamed in Others', () => {
      const groups = groupCasesByAgent(
        [makeCase({ id: 'a', originAgentName: 'OldName' }), makeCase({ id: 'b', originAgentName: 'Dev' })],
        { knownAgentNames: KNOWN }
      )

      expect(groups.map((g) => [g.label, ids(g.cases)])).toEqual([
        ['Dev', ['b']],
        ['Others', ['a']],
      ])
    })

    it('trusts the origin agent when the known agents are not available (null), instead of dumping all in Others', () => {
      const groups = groupCasesByAgent([makeCase({ id: 'a', originAgentName: 'Whoever' }), makeCase({ id: 'b' })], {
        knownAgentNames: null,
      })

      expect(groups.map((g) => [g.label, ids(g.cases)])).toEqual([
        ['Whoever', ['a']],
        ['Others', ['b']],
      ])
    })
  })

  describe('group ordering', () => {
    it('sorts groups by their most recent case and keeps Others last whatever its activity', () => {
      const groups = groupCasesByAgent(
        [
          makeCase({ id: 'other', modified: '2026-12-31' }),
          makeCase({ id: 'dev-old', originAgentName: 'Dev', modified: '2026-01-01' }),
          makeCase({ id: 'dev-new', originAgentName: 'Dev', modified: '2026-03-01' }),
          makeCase({ id: 'writer', originAgentName: 'Writer', modified: '2026-02-01' }),
        ],
        { knownAgentNames: KNOWN }
      )

      expect(groups.map((g) => g.label)).toEqual(['Dev', 'Writer', 'Others'])
      expect(groupAt(groups, 0).lastActivity).toBe('2026-03-01')
    })

    it('falls back to created when a case was never modified', () => {
      const groups = groupCasesByAgent(
        [
          makeCase({ id: 'a', originAgentName: 'Dev', created: '2026-01-01' }),
          makeCase({ id: 'b', originAgentName: 'Writer', created: '2026-02-01' }),
        ],
        { knownAgentNames: KNOWN }
      )

      expect(groups.map((g) => g.label)).toEqual(['Writer', 'Dev'])
    })

    it('orders groups without any date by label, so the order is deterministic', () => {
      const groups = groupCasesByAgent(
        [makeCase({ id: 'a', originAgentName: 'Writer' }), makeCase({ id: 'b', originAgentName: 'Dev' })],
        { knownAgentNames: KNOWN }
      )

      expect(groups.map((g) => g.label)).toEqual(['Dev', 'Writer'])
    })
  })

  describe('case ordering inside a group', () => {
    it('keeps the order of the list: favorites first, then most recent first', () => {
      const groups = groupCasesByAgent(
        [
          makeCase({ id: 'old', originAgentName: 'Dev', modified: '2026-01-01' }),
          makeCase({ id: 'new', originAgentName: 'Dev', modified: '2026-03-01' }),
          makeCase({ id: 'fav-old', originAgentName: 'Dev', modified: '2025-12-01', favorite: true }),
          makeCase({ id: 'mid', originAgentName: 'Dev', modified: '2026-02-01' }),
        ],
        { knownAgentNames: KNOWN }
      )

      expect(ids(groupAt(groups, 0).cases)).toEqual(['fav-old', 'new', 'mid', 'old'])
    })

    it('keeps the input order for cases with the same recency', () => {
      const groups = groupCasesByAgent(
        [
          makeCase({ id: 'first', originAgentName: 'Dev', modified: '2026-01-01' }),
          makeCase({ id: 'second', originAgentName: 'Dev', modified: '2026-01-01' }),
        ],
        { knownAgentNames: KNOWN }
      )

      expect(ids(groupAt(groups, 0).cases)).toEqual(['first', 'second'])
    })

    it('does not mutate its input', () => {
      const input = [
        makeCase({ id: 'a', originAgentName: 'Dev', modified: '2026-01-01' }),
        makeCase({ id: 'b', originAgentName: 'Dev', modified: '2026-02-01' }),
      ]

      groupCasesByAgent(input, { knownAgentNames: KNOWN })

      expect(ids(input)).toEqual(['a', 'b'])
    })
  })

  describe('empty groups', () => {
    it('returns no group for an empty list', () => {
      expect(groupCasesByAgent([], { knownAgentNames: KNOWN })).toEqual([])
    })

    it('returns no group when only sub-cases remain', () => {
      expect(
        groupCasesByAgent([makeCase({ id: 'child', originAgentName: 'Dev', parentCaseId: 'p' })], {
          knownAgentNames: KNOWN,
        })
      ).toEqual([])
    })

    it('does not render a group for a known agent that has no case', () => {
      const groups = groupCasesByAgent([makeCase({ id: 'a', originAgentName: 'Dev' })], { knownAgentNames: KNOWN })

      expect(groups.map((g) => g.label)).toEqual(['Dev'])
    })
  })
})

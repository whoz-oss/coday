import {
  CaseListPreferencesService,
  COLLAPSED_GROUPS_STORAGE_KEY_PREFIX,
  GROUP_BY_AGENT_STORAGE_KEY,
} from './case-list-preferences.service'

describe('CaseListPreferencesService', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  describe('groupByAgent', () => {
    it('is off when nothing is stored', () => {
      expect(new CaseListPreferencesService().groupByAgent()).toBe(false)
    })

    it('reads the persisted choice on init', () => {
      localStorage.setItem(GROUP_BY_AGENT_STORAGE_KEY, 'true')
      expect(new CaseListPreferencesService().groupByAgent()).toBe(true)
    })

    it.each(['false', 'yes', ''])('is off for the stored value %p', (stored) => {
      localStorage.setItem(GROUP_BY_AGENT_STORAGE_KEY, stored)
      expect(new CaseListPreferencesService().groupByAgent()).toBe(false)
    })

    it('persists and reflects the choice, and a new instance reads it back', () => {
      const service = new CaseListPreferencesService()

      service.setGroupByAgent(true)
      expect(service.groupByAgent()).toBe(true)
      expect(localStorage.getItem(GROUP_BY_AGENT_STORAGE_KEY)).toBe('true')
      expect(new CaseListPreferencesService().groupByAgent()).toBe(true)

      service.setGroupByAgent(false)
      expect(service.groupByAgent()).toBe(false)
      expect(new CaseListPreferencesService().groupByAgent()).toBe(false)
    })
  })

  describe('collapsed groups', () => {
    it('has nothing collapsed when nothing is stored', () => {
      expect(new CaseListPreferencesService().collapsedGroups('ns-1').size).toBe(0)
    })

    it('collapses then expands a group, persisting under a per-namespace key', () => {
      const service = new CaseListPreferencesService()

      service.toggleGroupCollapsed('ns-1', 'agent:dev')
      expect([...service.collapsedGroups('ns-1')]).toEqual(['agent:dev'])
      expect(localStorage.getItem(`${COLLAPSED_GROUPS_STORAGE_KEY_PREFIX}ns-1`)).toBe('["agent:dev"]')

      service.toggleGroupCollapsed('ns-1', 'agent:dev')
      expect(service.collapsedGroups('ns-1').size).toBe(0)
      expect(localStorage.getItem(`${COLLAPSED_GROUPS_STORAGE_KEY_PREFIX}ns-1`)).toBe('[]')
    })

    it('survives a reload: a new instance reads the stored keys', () => {
      new CaseListPreferencesService().toggleGroupCollapsed('ns-1', 'others')

      expect([...new CaseListPreferencesService().collapsedGroups('ns-1')]).toEqual(['others'])
    })

    it('keeps a collapsed key while its group does not exist, so it comes back collapsed', () => {
      const service = new CaseListPreferencesService()
      service.toggleGroupCollapsed('ns-1', 'agent:dev')
      service.toggleGroupCollapsed('ns-1', 'agent:writer')

      // Nothing prunes keys: the groups may disappear and reappear (no case, agent unavailable...).
      expect([...service.collapsedGroups('ns-1')].sort()).toEqual(['agent:dev', 'agent:writer'])
    })

    it('scopes the collapsed groups per namespace', () => {
      const service = new CaseListPreferencesService()
      service.toggleGroupCollapsed('ns-1', 'agent:dev')

      expect(service.collapsedGroups('ns-1').has('agent:dev')).toBe(true)
      expect(service.collapsedGroups('ns-2').has('agent:dev')).toBe(false)
    })

    it('falls back to a shared default scope without namespace', () => {
      const service = new CaseListPreferencesService()
      service.toggleGroupCollapsed(null, 'others')

      expect(localStorage.getItem(`${COLLAPSED_GROUPS_STORAGE_KEY_PREFIX}default`)).toBe('["others"]')
      expect(service.collapsedGroups(null).has('others')).toBe(true)
    })

    it.each(['not json', '{"a":1}', '"str"'])('ignores the corrupted stored value %p', (stored) => {
      localStorage.setItem(`${COLLAPSED_GROUPS_STORAGE_KEY_PREFIX}ns-1`, stored)
      expect(new CaseListPreferencesService().collapsedGroups('ns-1').size).toBe(0)
    })

    it('keeps only string keys of a stored array', () => {
      localStorage.setItem(`${COLLAPSED_GROUPS_STORAGE_KEY_PREFIX}ns-1`, '["agent:dev", 3, null]')
      expect([...new CaseListPreferencesService().collapsedGroups('ns-1')]).toEqual(['agent:dev'])
    })
  })
})

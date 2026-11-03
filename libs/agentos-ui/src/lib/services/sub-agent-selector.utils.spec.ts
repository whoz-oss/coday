import { grantsAllSubAgents, isSubAgentPattern, namedSubAgentKeys } from './sub-agent-selector.utils'

describe('sub-agent selector utils', () => {
  describe('grantsAllSubAgents', () => {
    it('is false without selectors', () => {
      expect(grantsAllSubAgents(undefined)).toBe(false)
      expect(grantsAllSubAgents([])).toBe(false)
    })

    it('finds the star among names, including when padded', () => {
      expect(grantsAllSubAgents(['Reviewer', ' * '])).toBe(true)
    })

    it('does not read a glob that merely contains a star as the whole grant', () => {
      expect(grantsAllSubAgents(['*Fixer'])).toBe(false)
    })
  })

  describe('namedSubAgentKeys', () => {
    it('lower-cases the names, since the server match is case-insensitive', () => {
      expect(namedSubAgentKeys(['Reviewer', 'bugFIXER'])).toEqual(new Set(['reviewer', 'bugfixer']))
    })

    it('drops the star, which is not a name', () => {
      expect(namedSubAgentKeys(['*', 'Reviewer'])).toEqual(new Set(['reviewer']))
    })

    it('keeps other globs: the form has to surface them somewhere to avoid dropping them', () => {
      expect(namedSubAgentKeys(['*Fixer'])).toEqual(new Set(['*fixer']))
    })

    it('ignores blank entries', () => {
      expect(namedSubAgentKeys(['  ', 'Reviewer'])).toEqual(new Set(['reviewer']))
    })

    it('is empty without selectors', () => {
      expect(namedSubAgentKeys(undefined)).toEqual(new Set())
    })
  })

  describe('isSubAgentPattern', () => {
    it.each([
      ['*', true],
      ['*Fixer', true],
      ['Fixer*', true],
      ['Reviewer', false],
      ['3f2504e0-4f89-41d3-9a0c-0305e82c3301', false],
    ])('reads %s as a pattern: %s', (selector, expected) => {
      expect(isSubAgentPattern(selector)).toBe(expected)
    })
  })
})

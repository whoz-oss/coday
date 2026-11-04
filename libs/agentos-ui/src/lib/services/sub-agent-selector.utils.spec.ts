import {
  grantsAllSubAgents,
  isGrantedByPattern,
  isSubAgentPattern,
  namedSubAgentKeys,
} from './sub-agent-selector.utils'

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

  // The regex these build is the one part of this file that mirrors server code
  // (`AgentServiceImpl.buildDelegationTools` with `globToRegex`). It drives the "via pattern" badge
  // and the "no matching agent" label, so a divergence makes the form misstate what the agent may
  // delegate to. These cases pin the mirror itself, not just the function around it.
  describe('isGrantedByPattern', () => {
    it('is false without selectors', () => {
      expect(isGrantedByPattern('Reviewer', undefined)).toBe(false)
      expect(isGrantedByPattern('Reviewer', [])).toBe(false)
    })

    it('matches a glob against the name, anchored at both ends', () => {
      expect(isGrantedByPattern('BugFixer', ['*Fixer'])).toBe(true)
      expect(isGrantedByPattern('FixerHelper', ['Fixer*'])).toBe(true)
      expect(isGrantedByPattern('BugFixerV2', ['*Fixer'])).toBe(false)
    })

    it('ignores case, as the server regex does', () => {
      expect(isGrantedByPattern('bugfixer', ['*FIXER'])).toBe(true)
    })

    it('reads a padded selector, which the stored value may carry', () => {
      expect(isGrantedByPattern('BugFixer', ['  *Fixer  '])).toBe(true)
    })

    it('does not count a plain name: that row is ticked by name, not by a pattern', () => {
      expect(isGrantedByPattern('Reviewer', ['Reviewer'])).toBe(false)
    })

    it('excludes the bare star, which the form shows as its own toggle', () => {
      expect(isGrantedByPattern('Reviewer', ['*'])).toBe(false)
    })

    it('escapes the characters the server escapes, so they match literally', () => {
      expect(isGrantedByPattern('A.BCore', ['A.B*'])).toBe(true)
      expect(isGrantedByPattern('AxBCore', ['A.B*'])).toBe(false)
      expect(isGrantedByPattern('Team(A)Core', ['Team(A)*'])).toBe(true)
      expect(isGrantedByPattern('Team|A', ['Team|A*'])).toBe(true)
    })

    it('leaves ? and + as quantifiers, which is what globToRegex does with them', () => {
      // globToRegex escapes \ . [ ] { } ( ) ^ $ | and nothing else, so these two reach the regex
      // engine intact. Escaping them here made the form answer no where the run answers yes.
      expect(isGrantedByPattern('FierAgent', ['Fix?er*'])).toBe(true)
      expect(isGrantedByPattern('FixerAgent', ['Fix?er*'])).toBe(true)
      expect(isGrantedByPattern('Fixxer', ['Fix?er*'])).toBe(false)
      expect(isGrantedByPattern('FixeerCore', ['Fixe+r*'])).toBe(true)
    })

    it('needs only one selector to match', () => {
      expect(isGrantedByPattern('BugFixer', ['Reviewer', 'Nope*', '*Fixer'])).toBe(true)
    })
  })
})

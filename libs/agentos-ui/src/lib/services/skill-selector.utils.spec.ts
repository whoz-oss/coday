import { grantsAllSkills, namedSelectorKeys, normalizeSkillSelector, skillGrantOf } from './skill-selector.utils'

describe('skill selector utils', () => {
  describe('normalizeSkillSelector', () => {
    it.each([
      ['  deploy  ', 'deploy'],
      ['deploy/SKILL.md', 'deploy'],
      ['deploy/', 'deploy'],
      ['/deploy', 'deploy'],
      ['/deploy/SKILL.md', 'deploy'],
    ])('reads %s as %s', (selector, expected) => {
      expect(normalizeSkillSelector(selector)).toBe(expected)
    })

    it('leaves an inner slash alone, since only the SKILL.md suffix is structural', () => {
      expect(normalizeSkillSelector('team/deploy')).toBe('team/deploy')
    })
  })

  describe('grantsAllSkills', () => {
    it('is false without selectors', () => {
      expect(grantsAllSkills(undefined)).toBe(false)
      expect(grantsAllSkills([])).toBe(false)
    })

    it('finds the star among names, including when padded', () => {
      expect(grantsAllSkills(['deploy', ' * '])).toBe(true)
    })
  })

  describe('namedSelectorKeys', () => {
    it('drops the star and blanks, and lower-cases the rest', () => {
      expect(namedSelectorKeys(['*', '', '  ', 'Deploy', 'migrate/SKILL.md'])).toEqual(new Set(['deploy', 'migrate']))
    })
  })

  describe('skillGrantOf', () => {
    it('reports a named grant whatever the case or the path form', () => {
      expect(skillGrantOf(['Deploy/SKILL.md'], 'deploy')).toBe('named')
    })

    it('reports a star grant when the name is not listed', () => {
      expect(skillGrantOf(['*'], 'deploy')).toBe('all')
    })

    it('prefers the named grant over the star, since only that one breaks on deletion', () => {
      expect(skillGrantOf(['*', 'deploy'], 'deploy')).toBe('named')
    })

    it('reports no grant otherwise', () => {
      expect(skillGrantOf(['migrate'], 'deploy')).toBe('none')
      expect(skillGrantOf(undefined, 'deploy')).toBe('none')
    })
  })
})

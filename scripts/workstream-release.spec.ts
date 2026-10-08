import { isValidSlug, isValidWorkstreamVersion } from './utils/workstream-validators'

describe('isValidSlug', () => {
  describe('valid slugs', () => {
    it('accepts a single lowercase word', () => {
      expect(isValidSlug('talent')).toBe(true)
    })

    it('accepts hyphenated lowercase words', () => {
      expect(isValidSlug('talent-portal')).toBe(true)
    })

    it('accepts multiple hyphen-separated segments', () => {
      expect(isValidSlug('my-feature-slug')).toBe(true)
    })

    it('accepts digits mixed with letters', () => {
      expect(isValidSlug('feature1')).toBe(true)
      expect(isValidSlug('v2-integration')).toBe(true)
    })

    it('accepts a slug that is all digits', () => {
      expect(isValidSlug('123')).toBe(true)
    })
  })

  describe('invalid slugs', () => {
    it('rejects uppercase letters', () => {
      expect(isValidSlug('Talent_Portal')).toBe(false)
      expect(isValidSlug('TalentPortal')).toBe(false)
    })

    it('rejects underscores', () => {
      expect(isValidSlug('talent_portal')).toBe(false)
    })

    it('rejects spaces', () => {
      expect(isValidSlug('talent portal')).toBe(false)
    })

    it('rejects a leading hyphen', () => {
      expect(isValidSlug('-foo')).toBe(false)
    })

    it('rejects a trailing hyphen', () => {
      expect(isValidSlug('foo-')).toBe(false)
    })

    it('rejects a slug that is only a hyphen', () => {
      expect(isValidSlug('-')).toBe(false)
    })

    it('rejects consecutive hyphens', () => {
      expect(isValidSlug('foo--bar')).toBe(false)
    })

    it('rejects an empty string', () => {
      expect(isValidSlug('')).toBe(false)
    })

    it('rejects dots', () => {
      expect(isValidSlug('talent.portal')).toBe(false)
    })

    it('rejects slashes', () => {
      expect(isValidSlug('talent/portal')).toBe(false)
    })
  })
})

describe('isValidWorkstreamVersion', () => {
  describe('valid versions', () => {
    it('accepts a canonical workstream version', () => {
      expect(isValidWorkstreamVersion('0.241.0-talent-portal.47', 'talent-portal')).toBe(true)
    })

    it('accepts run number 0', () => {
      expect(isValidWorkstreamVersion('1.0.0-talent-portal.0', 'talent-portal')).toBe(true)
    })

    it('accepts large run numbers', () => {
      expect(isValidWorkstreamVersion('0.241.0-talent-portal.9999', 'talent-portal')).toBe(true)
    })

    it('accepts a single-word slug', () => {
      expect(isValidWorkstreamVersion('0.1.0-myfeature.3', 'myfeature')).toBe(true)
    })
  })

  describe('invalid versions', () => {
    it('rejects a plain semver with no prerelease', () => {
      expect(isValidWorkstreamVersion('0.241.0', 'talent-portal')).toBe(false)
    })

    it('rejects a version whose slug does not match the provided slug', () => {
      expect(isValidWorkstreamVersion('0.241.0-other-slug.47', 'talent-portal')).toBe(false)
    })

    it('rejects a version with an uppercase slug (slug: talent-portal)', () => {
      expect(isValidWorkstreamVersion('0.241.0-Talent-Portal.47', 'talent-portal')).toBe(false)
    })

    it('rejects a version missing the run-number suffix', () => {
      expect(isValidWorkstreamVersion('0.241.0-talent-portal', 'talent-portal')).toBe(false)
    })

    it('rejects a version with a non-numeric run-number suffix', () => {
      expect(isValidWorkstreamVersion('0.241.0-talent-portal.abc', 'talent-portal')).toBe(false)
    })

    it('rejects an empty string', () => {
      expect(isValidWorkstreamVersion('', 'talent-portal')).toBe(false)
    })

    it('rejects a version with extra leading text (e.g. v-prefix)', () => {
      expect(isValidWorkstreamVersion('v0.241.0-talent-portal.47', 'talent-portal')).toBe(false)
    })

    it('rejects a version with build metadata suffix', () => {
      expect(isValidWorkstreamVersion('0.241.0-talent-portal.47+build.1', 'talent-portal')).toBe(false)
    })
  })
})

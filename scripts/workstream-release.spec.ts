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
    it('accepts the canonical 0.0.0 workstream version produced by the build', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-talent-portal.47', 'talent-portal')).toBe(true)
    })

    it('accepts counter 0', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-talent-portal.0', 'talent-portal')).toBe(true)
    })

    it('accepts large counters', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-talent-portal.9999', 'talent-portal')).toBe(true)
    })

    it('accepts a single-word slug', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-myfeature.3', 'myfeature')).toBe(true)
    })

    it('accepts a non-zero base (shape is validated, base policy is not)', () => {
      // The build uses a fixed 0.0.0 base, but this function validates SHAPE only.
      // Pinning 0.0.0 here would make the base decision impossible to revisit without
      // editing the validator.
      expect(isValidWorkstreamVersion('3.28.1-workstream-talent-portal.47', 'talent-portal')).toBe(true)
    })
  })

  describe('invalid versions', () => {
    it('rejects a version missing the literal "workstream-" prefix', () => {
      // This is the OLD format. It must now be rejected -- the prefix is what makes a
      // workstream artifact self-identifying from its version alone.
      expect(isValidWorkstreamVersion('0.0.0-talent-portal.47', 'talent-portal')).toBe(false)
      expect(isValidWorkstreamVersion('3.28.1-talent-portal.47', 'talent-portal')).toBe(false)
    })

    it('rejects a plain semver with no prerelease', () => {
      expect(isValidWorkstreamVersion('0.241.0', 'talent-portal')).toBe(false)
    })

    it('rejects a version whose slug does not match the provided slug', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-other-slug.47', 'talent-portal')).toBe(false)
    })

    it('rejects a slug that merely has the right suffix', () => {
      // Guards against an unanchored pattern: 'not-forge' must not satisfy slug 'forge'.
      expect(isValidWorkstreamVersion('0.0.0-workstream-not-forge.1', 'forge')).toBe(false)
    })

    it('rejects a slug that merely has the right prefix', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-forge-extra.1', 'forge')).toBe(false)
    })

    it('rejects a doubled workstream prefix', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-workstream-forge.1', 'forge')).toBe(false)
    })

    it('rejects a version with an uppercase slug', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-Talent-Portal.47', 'talent-portal')).toBe(false)
    })

    it('rejects a version missing the counter suffix', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-talent-portal', 'talent-portal')).toBe(false)
    })

    it('rejects a version with a non-numeric counter suffix', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-talent-portal.abc', 'talent-portal')).toBe(false)
    })

    it('rejects an empty string', () => {
      expect(isValidWorkstreamVersion('', 'talent-portal')).toBe(false)
    })

    it('rejects a version with extra leading text (e.g. v-prefix)', () => {
      expect(isValidWorkstreamVersion('v0.0.0-workstream-talent-portal.47', 'talent-portal')).toBe(false)
    })

    it('rejects a version with build metadata suffix', () => {
      expect(isValidWorkstreamVersion('0.0.0-workstream-talent-portal.47+build.1', 'talent-portal')).toBe(false)
    })
  })
})

import { updateTomlVersion } from './update-toml-version'

const MOCK_TOML = `
[versions]
java = "25"
kotlin = "2.3.20"
springBoot = "3.5.7"

# AgentOS modules versions
agentosSdk = "0.115.2"
agentosService = "0.0.1-SNAPSHOT"

[libraries]
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib" }
`

describe('updateTomlVersion', () => {
  describe('correctly updates agentosSdk version', () => {
    it('handles a standard patch version', () => {
      const result = updateTomlVersion(MOCK_TOML, 'agentosSdk', '0.116.0')
      expect(result).toContain('agentosSdk = "0.116.0"')
    })

    it('handles a major version bump', () => {
      const result = updateTomlVersion(MOCK_TOML, 'agentosSdk', '1.0.0')
      expect(result).toContain('agentosSdk = "1.0.0"')
    })

    it('handles a double-digit major version', () => {
      const result = updateTomlVersion(MOCK_TOML, 'agentosSdk', '10.0.0')
      expect(result).toContain('agentosSdk = "10.0.0"')
    })

    it('handles a prerelease version', () => {
      const result = updateTomlVersion(MOCK_TOML, 'agentosSdk', '2.3.0-beta.1')
      expect(result).toContain('agentosSdk = "2.3.0-beta.1"')
    })

    it('works with a different key', () => {
      const result = updateTomlVersion(MOCK_TOML, 'agentosService', '1.2.3')
      expect(result).toContain('agentosService = "1.2.3"')
    })
  })

  describe('does not affect other entries', () => {
    it('leaves other agentos keys untouched when updating agentosSdk', () => {
      const result = updateTomlVersion(MOCK_TOML, 'agentosSdk', '1.0.0')
      expect(result).toContain('agentosService = "0.0.1-SNAPSHOT"')
    })

    it('leaves unrelated versions untouched', () => {
      const result = updateTomlVersion(MOCK_TOML, 'agentosSdk', '1.0.0')
      expect(result).toContain('kotlin = "2.3.20"')
      expect(result).toContain('springBoot = "3.5.7"')
    })

    it('preserves the full TOML structure', () => {
      const result = updateTomlVersion(MOCK_TOML, 'agentosSdk', '1.0.0')
      expect(result).toContain('[libraries]')
      expect(result).toContain('kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib" }')
    })
  })

  describe('edge cases', () => {
    it('is idempotent — applying the same version twice yields the same result', () => {
      const once = updateTomlVersion(MOCK_TOML, 'agentosSdk', '1.0.0')
      const twice = updateTomlVersion(once, 'agentosSdk', '1.0.0')
      expect(once).toEqual(twice)
    })

    it('returns content unchanged if the key is not present', () => {
      const tomlWithoutSdk = MOCK_TOML.replace(/agentosSdk.*\n/, '')
      const result = updateTomlVersion(tomlWithoutSdk, 'agentosSdk', '1.0.0')
      expect(result).toEqual(tomlWithoutSdk)
    })
  })
})

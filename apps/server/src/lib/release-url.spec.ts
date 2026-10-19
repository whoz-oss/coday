import { releaseBaseUrl, releaseTagForVersion } from './release-url'

describe('releaseTagForVersion', () => {
  it('maps a regular version to release/<version>', () => {
    expect(releaseTagForVersion('3.28.1')).toBe('release/3.28.1')
  })

  it('maps a workstream prerelease to workstream-<slug>-<n>', () => {
    expect(releaseTagForVersion('0.0.0-workstream-forge.32')).toBe('workstream-forge-32')
  })

  it('supports hyphenated slugs', () => {
    expect(releaseTagForVersion('0.0.0-workstream-talent-portal.4')).toBe('workstream-talent-portal-4')
  })

  it('keeps release/<version> for other prereleases', () => {
    expect(releaseTagForVersion('1.0.0-beta.1')).toBe('release/1.0.0-beta.1')
  })
})

describe('releaseBaseUrl', () => {
  it('builds the regular release URL', () => {
    expect(releaseBaseUrl('3.28.1')).toBe('https://github.com/whoz-oss/coday/releases/download/release/3.28.1')
  })

  it('builds the workstream release URL', () => {
    expect(releaseBaseUrl('0.0.0-workstream-forge.32')).toBe(
      'https://github.com/whoz-oss/coday/releases/download/workstream-forge-32'
    )
  })
})

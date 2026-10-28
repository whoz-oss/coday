import { buildFactoryProjectConfig } from './factory-gradle'

describe('buildFactoryProjectConfig', () => {
  describe('release block', () => {
    it('infers an empty manifestRootsToUpdate (no package.json for JVM projects)', () => {
      const result = buildFactoryProjectConfig('factory/factory-verification-core', 'factory-verification-core')
      expect(result.projects?.['factory/factory-verification-core']).toMatchObject({
        release: { version: { manifestRootsToUpdate: [] } },
      })
    })

    it('applies to all factory projects (factory-sdk)', () => {
      const result = buildFactoryProjectConfig('factory/factory-sdk', 'factory-sdk')
      expect(result.projects?.['factory/factory-sdk']).toMatchObject({
        release: { version: { manifestRootsToUpdate: [] } },
      })
    })
  })

  describe('nx-release-publish target', () => {
    it('infers a no-op echo command with empty dependsOn', () => {
      const result = buildFactoryProjectConfig('factory/factory-verification-core', 'factory-verification-core')
      const target = result.projects?.['factory/factory-verification-core']?.targets?.['nx-release-publish']
      expect(target).toMatchObject({
        executor: 'nx:run-commands',
        dependsOn: [],
        options: { command: "echo 'factory-verification-core published via Gradle in CI'" },
      })
    })

    it('interpolates the project name correctly', () => {
      const result = buildFactoryProjectConfig('factory/factory-sdk', 'factory-sdk')
      const target = result.projects?.['factory/factory-sdk']?.targets?.['nx-release-publish']
      expect(target?.options?.command).toBe("echo 'factory-sdk published via Gradle in CI'")
    })
  })

  describe('project key', () => {
    it('uses projectDir as the project map key', () => {
      const result = buildFactoryProjectConfig('factory/factory-sdk', 'factory-sdk')
      expect(result.projects).toHaveProperty('factory/factory-sdk')
    })
  })
})

import { buildProjectConfig } from './agentos-gradle'

describe('buildProjectConfig', () => {
  describe('release block', () => {
    it('infers an empty manifestRootsToUpdate', () => {
      const result = buildProjectConfig('agentos/agentos-service', 'agentos-service')
      expect(result.projects?.['agentos/agentos-service']).toMatchObject({
        release: { version: { manifestRootsToUpdate: [] } },
      })
    })
  })

  describe('build target', () => {
    it('infers the correct gradlew command', () => {
      const result = buildProjectConfig('agentos/agentos-service', 'agentos-service')
      const target = result.projects?.['agentos/agentos-service']?.targets?.['build']
      expect(target).toMatchObject({
        executor: 'nx:run-commands',
        cache: true,
        options: { command: './gradlew :agentos-service:build', cwd: 'agentos' },
        dependsOn: [{ target: 'build', projects: ['agentos-sdk'] }],
      })
    })

    it('interpolates the project name correctly', () => {
      const result = buildProjectConfig('agentos/agentos-datetime-plugin', 'agentos-datetime-plugin')
      const target = result.projects?.['agentos/agentos-datetime-plugin']?.targets?.['build']
      expect(target?.options?.command).toBe('./gradlew :agentos-datetime-plugin:build')
    })
  })

  describe('test target', () => {
    it('infers the correct gradlew command', () => {
      const result = buildProjectConfig('agentos/agentos-service', 'agentos-service')
      const target = result.projects?.['agentos/agentos-service']?.targets?.['test']
      expect(target).toMatchObject({
        executor: 'nx:run-commands',
        cache: true,
        options: { command: './gradlew :agentos-service:test', cwd: 'agentos' },
        dependsOn: ['build', { target: 'build', projects: ['agentos-sdk'] }],
      })
    })

    it('interpolates the project name correctly', () => {
      const result = buildProjectConfig('agentos/agentos-datetime-plugin', 'agentos-datetime-plugin')
      const target = result.projects?.['agentos/agentos-datetime-plugin']?.targets?.['test']
      expect(target?.options?.command).toBe('./gradlew :agentos-datetime-plugin:test')
    })
  })

  describe('clean target', () => {
    it('infers the correct gradlew command', () => {
      const result = buildProjectConfig('agentos/agentos-service', 'agentos-service')
      const target = result.projects?.['agentos/agentos-service']?.targets?.['clean']
      expect(target).toMatchObject({
        executor: 'nx:run-commands',
        cache: false,
        options: { command: './gradlew :agentos-service:clean', cwd: 'agentos' },
      })
    })
  })

  describe('nx-release-publish target', () => {
    it('infers a no-op echo command', () => {
      const result = buildProjectConfig('agentos/agentos-service', 'agentos-service')
      const target = result.projects?.['agentos/agentos-service']?.targets?.['nx-release-publish']
      expect(target).toMatchObject({
        executor: 'nx:run-commands',
        options: { command: "echo 'agentos-service published via Gradle in CI'" },
      })
    })

    it('interpolates the project name correctly', () => {
      const result = buildProjectConfig('agentos/agentos-datetime-plugin', 'agentos-datetime-plugin')
      const target = result.projects?.['agentos/agentos-datetime-plugin']?.targets?.['nx-release-publish']
      expect(target?.options?.command).toBe("echo 'agentos-datetime-plugin published via Gradle in CI'")
    })
  })

  describe('publish target', () => {
    it('infers the correct gradlew command', () => {
      const result = buildProjectConfig('agentos/agentos-service', 'agentos-service')
      const target = result.projects?.['agentos/agentos-service']?.targets?.['publish']
      expect(target).toMatchObject({
        executor: 'nx:run-commands',
        cache: false,
        options: {
          command: './gradlew :agentos-service:publish',
          cwd: 'agentos',
        },
        dependsOn: ['build'],
      })
    })

    it('interpolates the project name correctly', () => {
      const result = buildProjectConfig('agentos/agentos-datetime-plugin', 'agentos-datetime-plugin')
      const target = result.projects?.['agentos/agentos-datetime-plugin']?.targets?.['publish']
      expect(target?.options?.command).toBe('./gradlew :agentos-datetime-plugin:publish')
    })
  })

  describe('project key', () => {
    it('uses projectDir as the project map key', () => {
      const result = buildProjectConfig('agentos/agentos-file-plugin', 'agentos-file-plugin')
      expect(result.projects).toHaveProperty('agentos/agentos-file-plugin')
    })
  })
})

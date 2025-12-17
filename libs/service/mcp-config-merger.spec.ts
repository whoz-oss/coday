import { McpServerConfig } from '@coday/model/mcp-server-config'
import { mergeMcpConfigs } from './mcp-config-merger'

describe('McpConfigMerger', () => {
  // Store original environment
  const originalEnv = process.env

  beforeEach(() => {
    // Mock process.env with only the variables we want to test
    // This makes tests deterministic and independent of actual environment
    process.env = {
      PATH: '/usr/local/bin:/usr/bin:/bin',
      HOME: '/home/testuser',
      USER: 'testuser',
      SHELL: '/bin/bash',
      TMPDIR: '/tmp',
      TERM: 'xterm-256color',
      LANG: 'en_US.UTF-8',
      COLORTERM: 'truecolor',
    }
  })

  afterEach(() => {
    // Restore original environment after each test
    process.env = originalEnv
  })
  describe('merge', () => {
    it('should merge server configurations with correct precedence', () => {
      // Note: Default whitelist vars (PATH, HOME, USER, etc.) are always included
      const codayServers: McpServerConfig[] = [
        {
          id: 'github',
          name: 'GIT-PLATFORM',
          enabled: true,
          args: ['run', '-i', '--rm', '-e', 'GITHUB_PERSONAL_ACCESS_TOKEN', 'ghcr.io/github/github-mcp-server'],
          debug: false,
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'github',
          name: 'GIT-PLATFORM',
          command: '/usr/local/bin/docker',
          args: ['--network=host'],
          env: { GITHUB_PERSONAL_ACCESS_TOKEN: 'project-token' },
          debug: true,
          enabled: true,
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'github',
          name: 'GIT-PLATFORM',
          command: '/path/to/user/docker',
          env: { GITHUB_PERSONAL_ACCESS_TOKEN: 'user-secret-token' },
          enabled: false,
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      expect(result).toHaveLength(1)

      const mergedServer = result[0]!

      // Basic properties: later levels win
      expect(mergedServer.id).toBe('github')
      expect(mergedServer.name).toBe('GIT-PLATFORM')
      expect(mergedServer.command).toBe('/path/to/user/docker') // USER level wins

      // Args: aggregated from all levels in order
      expect(mergedServer.args).toEqual([
        'run',
        '-i',
        '--rm',
        '-e',
        'GITHUB_PERSONAL_ACCESS_TOKEN',
        'ghcr.io/github/github-mcp-server', // CODAY
        '--network=host', // PROJECT
        // USER had no args to add
      ])

      // Debug: true if any level sets it to true
      expect(mergedServer.debug).toBe(true) // PROJECT set it to true

      // Enabled: last level wins
      expect(mergedServer.enabled).toBe(false) // USER level wins

      // Env: later levels override, plus default whitelist vars
      expect(mergedServer.env).toEqual({
        // Default whitelist vars (always included)
        PATH: '/usr/local/bin:/usr/bin:/bin',
        HOME: '/home/testuser',
        USER: 'testuser',
        SHELL: '/bin/bash',
        TMPDIR: '/tmp',
        TERM: 'xterm-256color',
        LANG: 'en_US.UTF-8',
        COLORTERM: 'truecolor',
        // Custom env vars
        GITHUB_PERSONAL_ACCESS_TOKEN: 'user-secret-token', // USER level wins
      })
    })

    it('should handle servers that exist only at some levels', () => {
      const codayServers: McpServerConfig[] = [
        {
          id: 'fetch',
          name: 'FETCH',
          command: 'uvx',
          args: ['mcp-server-fetch'],
          enabled: true,
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'project-only',
          name: 'Project Only',
          command: 'project-command',
          enabled: true,
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'user-only',
          name: 'User Only',
          command: 'user-command',
          enabled: true,
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      expect(result).toHaveLength(3)
      expect(result.map((s) => s.id)).toContain('fetch')
      expect(result.map((s) => s.id)).toContain('project-only')
      expect(result.map((s) => s.id)).toContain('user-only')
    })

    it('should properly aggregate allowedTools from all levels', () => {
      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
          allowedTools: ['read_file', 'write_file'],
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          allowedTools: ['list_files', 'delete_file'],
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          allowedTools: ['search_files'],
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      expect(result[0]?.allowedTools).toEqual([
        'read_file',
        'write_file', // CODAY
        'list_files',
        'delete_file', // PROJECT
        'search_files', // USER
      ])
    })

    it('should handle undefined allowedTools correctly', () => {
      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
          // no allowedTools
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          allowedTools: ['some_tool'],
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          // no allowedTools
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      expect(result[0]?.allowedTools).toEqual(['some_tool'])
    })

    it('should handle debug flag correctly - true if any level sets it', () => {
      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
          debug: false,
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          debug: true,
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          debug: false, // Even though USER sets it to false, PROJECT set it to true
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      expect(result[0]?.debug).toBe(true)
    })

    it('should handle enabled flag correctly - last level wins', () => {
      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          enabled: false,
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          // enabled is undefined at user level
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      // Since USER level doesn't specify enabled, PROJECT level (false) should win
      expect(result[0]?.enabled).toBe(false)
    })

    it('should apply safe defaults for new servers', () => {
      const codayServers: McpServerConfig[] = []
      const projectServers: McpServerConfig[] = []
      const userServers: McpServerConfig[] = [
        {
          id: 'minimal',
          name: 'Minimal Server',
          command: 'minimal-command',
          // No enabled, debug, args, env specified
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      expect(result[0]).toEqual({
        id: 'minimal',
        name: 'Minimal Server',
        command: 'minimal-command',
        enabled: true, // Default
        debug: false, // Default
        args: [], // Default
        env: {
          // Default whitelist vars are always included
          PATH: '/usr/local/bin:/usr/bin:/bin',
          HOME: '/home/testuser',
          USER: 'testuser',
          SHELL: '/bin/bash',
          TMPDIR: '/tmp',
          TERM: 'xterm-256color',
          LANG: 'en_US.UTF-8',
          COLORTERM: 'truecolor',
        },
      })
    })

    it('should handle empty arrays correctly', () => {
      const result = mergeMcpConfigs([], [], [])
      expect(result).toEqual([])
    })

    it('should preserve environment variables from all levels with proper override', () => {
      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
          env: {
            BASE_VAR: 'base-value',
            SHARED_VAR: 'coday-value',
          },
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          env: {
            PROJECT_VAR: 'project-value',
            SHARED_VAR: 'project-value',
          },
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          env: {
            USER_VAR: 'user-value',
            SHARED_VAR: 'user-value',
          },
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      expect(result[0]?.env).toEqual({
        // Default whitelist vars
        PATH: '/usr/local/bin:/usr/bin:/bin',
        HOME: '/home/testuser',
        USER: 'testuser',
        SHELL: '/bin/bash',
        TMPDIR: '/tmp',
        TERM: 'xterm-256color',
        LANG: 'en_US.UTF-8',
        COLORTERM: 'truecolor',
        // Custom vars from config
        BASE_VAR: 'base-value', // From CODAY
        PROJECT_VAR: 'project-value', // From PROJECT
        USER_VAR: 'user-value', // From USER
        SHARED_VAR: 'user-value', // USER overrides PROJECT overrides CODAY
      })
    })

    it('should fall back to process.env for variables listed in envVarNames when not set', () => {
      // Add test-specific env var
      process.env.GITHUB_PERSONAL_ACCESS_TOKEN = 'env-token-value'
      const codayServers: McpServerConfig[] = [
        {
          id: 'github',
          name: 'GIT-PLATFORM',
          command: 'docker',
          enabled: true,
          whiteListedHostEnvVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN'],
          // No env specified
        },
      ]

      const result = mergeMcpConfigs(codayServers, [], [])

      expect(result[0]?.env).toEqual({
        // Default whitelist vars
        PATH: '/usr/local/bin:/usr/bin:/bin',
        HOME: '/home/testuser',
        USER: 'testuser',
        SHELL: '/bin/bash',
        TMPDIR: '/tmp',
        TERM: 'xterm-256color',
        LANG: 'en_US.UTF-8',
        COLORTERM: 'truecolor',
        // Variable from envVarNames
        GITHUB_PERSONAL_ACCESS_TOKEN: 'env-token-value',
      })
    })

    it('should not override GITHUB_PERSONAL_ACCESS_TOKEN if explicitly set in config', () => {
      // Add test-specific env var
      process.env.GITHUB_PERSONAL_ACCESS_TOKEN = 'env-token-value'
      const codayServers: McpServerConfig[] = [
        {
          id: 'github',
          name: 'GIT-PLATFORM',
          command: 'docker',
          enabled: true,
          whiteListedHostEnvVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN'],
          env: {
            GITHUB_PERSONAL_ACCESS_TOKEN: 'config-token-value',
          },
        },
      ]

      const result = mergeMcpConfigs(codayServers, [], [])

      expect(result[0]?.env).toEqual({
        // Default whitelist vars
        PATH: '/usr/local/bin:/usr/bin:/bin',
        HOME: '/home/testuser',
        USER: 'testuser',
        SHELL: '/bin/bash',
        TMPDIR: '/tmp',
        TERM: 'xterm-256color',
        LANG: 'en_US.UTF-8',
        COLORTERM: 'truecolor',
        // Config value overrides envVarNames
        GITHUB_PERSONAL_ACCESS_TOKEN: 'config-token-value',
      })
    })

    it('should apply env fallback when merging multiple levels', () => {
      // Add test-specific env var
      process.env.GITHUB_PERSONAL_ACCESS_TOKEN = 'env-token-value'
      const codayServers: McpServerConfig[] = [
        {
          id: 'github',
          name: 'GIT-PLATFORM',
          command: 'docker',
          enabled: true,
          args: ['-e', 'GITHUB_PERSONAL_ACCESS_TOKEN'],
          whiteListedHostEnvVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN'],
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'github',
          name: 'GIT-PLATFORM',
          env: {
            OTHER_VAR: 'other-value',
          },
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, [])

      expect(result[0]?.env).toEqual({
        // Default whitelist vars
        PATH: '/usr/local/bin:/usr/bin:/bin',
        HOME: '/home/testuser',
        USER: 'testuser',
        SHELL: '/bin/bash',
        TMPDIR: '/tmp',
        TERM: 'xterm-256color',
        LANG: 'en_US.UTF-8',
        COLORTERM: 'truecolor',
        // Variables from config and envVarNames
        GITHUB_PERSONAL_ACCESS_TOKEN: 'env-token-value',
        OTHER_VAR: 'other-value',
      })
    })

    it('should not fall back to process.env for variables not listed in envVarNames', () => {
      // Add a variable that's NOT in envVarNames
      process.env.SOME_OTHER_TOKEN = 'should-not-be-used'
      // Remove GITHUB_PERSONAL_ACCESS_TOKEN to test that it's not picked up
      delete process.env.GITHUB_PERSONAL_ACCESS_TOKEN

      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
          whiteListedHostEnvVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN'], // Only this one should be picked up, but it's not in process.env
          // No env specified
        },
      ]

      const result = mergeMcpConfigs(codayServers, [], [])

      // Should only have default whitelist vars because:
      // - SOME_OTHER_TOKEN is in process.env but not in envVarNames or default whitelist
      // - GITHUB_PERSONAL_ACCESS_TOKEN is in envVarNames but not in process.env
      expect(result[0]?.env).toEqual({
        // Only default whitelist vars
        PATH: '/usr/local/bin:/usr/bin:/bin',
        HOME: '/home/testuser',
        USER: 'testuser',
        SHELL: '/bin/bash',
        TMPDIR: '/tmp',
        TERM: 'xterm-256color',
        LANG: 'en_US.UTF-8',
        COLORTERM: 'truecolor',
      })
    })

    it('should merge envVarNames from multiple levels', () => {
      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
          whiteListedHostEnvVarNames: ['VAR1', 'VAR2'],
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          whiteListedHostEnvVarNames: ['VAR2', 'VAR3'], // VAR2 is duplicate
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          whiteListedHostEnvVarNames: ['VAR4'],
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      // Should have unique values from all levels
      expect(result[0]?.whiteListedHostEnvVarNames).toEqual(['VAR1', 'VAR2', 'VAR3', 'VAR4'])
    })

    it('should handle multiple environment variables in envVarNames', () => {
      // Add test-specific env vars
      process.env.GITHUB_PERSONAL_ACCESS_TOKEN = 'github-token'
      process.env.API_KEY = 'api-key-value'
      process.env.SECRET_TOKEN = 'secret-value'
      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
          whiteListedHostEnvVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN', 'API_KEY', 'SECRET_TOKEN'],
          // No env specified
        },
      ]

      const result = mergeMcpConfigs(codayServers, [], [])

      expect(result[0]?.env).toEqual({
        // Default whitelist vars
        PATH: '/usr/local/bin:/usr/bin:/bin',
        HOME: '/home/testuser',
        USER: 'testuser',
        SHELL: '/bin/bash',
        TMPDIR: '/tmp',
        TERM: 'xterm-256color',
        LANG: 'en_US.UTF-8',
        COLORTERM: 'truecolor',
        // Variables from envVarNames
        GITHUB_PERSONAL_ACCESS_TOKEN: 'github-token',
        API_KEY: 'api-key-value',
        SECRET_TOKEN: 'secret-value',
      })
    })
  })
})

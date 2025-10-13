import { McpServerConfig } from '../model/mcp-server-config'
import { mergeMcpConfigs } from './mcp-config-merger'

describe('McpConfigMerger', () => {
  describe('merge', () => {
    it('should merge server configurations with correct precedence', () => {
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

      // Env: later levels override
      expect(mergedServer.env).toEqual({
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
      // Clear env for this test
      const originalEnv = process.env.GITHUB_PERSONAL_ACCESS_TOKEN
      delete process.env.GITHUB_PERSONAL_ACCESS_TOKEN

      try {
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
          env: {}, // Default
        })
      } finally {
        // Restore original env
        if (originalEnv !== undefined) {
          process.env.GITHUB_PERSONAL_ACCESS_TOKEN = originalEnv
        }
      }
    })

    it('should handle empty arrays correctly', () => {
      const result = mergeMcpConfigs([], [], [])
      expect(result).toEqual([])
    })

    it('should preserve environment variables from all levels with proper override', () => {
      // Clear env for this test
      const originalEnv = process.env.GITHUB_PERSONAL_ACCESS_TOKEN
      delete process.env.GITHUB_PERSONAL_ACCESS_TOKEN

      try {
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
          BASE_VAR: 'base-value', // From CODAY
          PROJECT_VAR: 'project-value', // From PROJECT
          USER_VAR: 'user-value', // From USER
          SHARED_VAR: 'user-value', // USER overrides PROJECT overrides CODAY
        })
      } finally {
        // Restore original env
        if (originalEnv !== undefined) {
          process.env.GITHUB_PERSONAL_ACCESS_TOKEN = originalEnv
        }
      }
    })

    it('should fall back to process.env for variables listed in envVarNames when not set', () => {
      // Set up process.env
      const originalEnv = process.env.GITHUB_PERSONAL_ACCESS_TOKEN
      process.env.GITHUB_PERSONAL_ACCESS_TOKEN = 'env-token-value'

      try {
        const codayServers: McpServerConfig[] = [
          {
            id: 'github',
            name: 'GIT-PLATFORM',
            command: 'docker',
            enabled: true,
            envVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN'],
            // No env specified
          },
        ]

        const result = mergeMcpConfigs(codayServers, [], [])

        expect(result[0]?.env).toEqual({
          GITHUB_PERSONAL_ACCESS_TOKEN: 'env-token-value',
        })
      } finally {
        // Restore original env
        if (originalEnv !== undefined) {
          process.env.GITHUB_PERSONAL_ACCESS_TOKEN = originalEnv
        } else {
          delete process.env.GITHUB_PERSONAL_ACCESS_TOKEN
        }
      }
    })

    it('should not override GITHUB_PERSONAL_ACCESS_TOKEN if explicitly set in config', () => {
      // Set up process.env
      const originalEnv = process.env.GITHUB_PERSONAL_ACCESS_TOKEN
      process.env.GITHUB_PERSONAL_ACCESS_TOKEN = 'env-token-value'

      try {
        const codayServers: McpServerConfig[] = [
          {
            id: 'github',
            name: 'GIT-PLATFORM',
            command: 'docker',
            enabled: true,
            envVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN'],
            env: {
              GITHUB_PERSONAL_ACCESS_TOKEN: 'config-token-value',
            },
          },
        ]

        const result = mergeMcpConfigs(codayServers, [], [])

        expect(result[0]?.env).toEqual({
          GITHUB_PERSONAL_ACCESS_TOKEN: 'config-token-value',
        })
      } finally {
        // Restore original env
        if (originalEnv !== undefined) {
          process.env.GITHUB_PERSONAL_ACCESS_TOKEN = originalEnv
        } else {
          delete process.env.GITHUB_PERSONAL_ACCESS_TOKEN
        }
      }
    })

    it('should apply env fallback when merging multiple levels', () => {
      // Set up process.env
      const originalEnv = process.env.GITHUB_PERSONAL_ACCESS_TOKEN
      process.env.GITHUB_PERSONAL_ACCESS_TOKEN = 'env-token-value'

      try {
        const codayServers: McpServerConfig[] = [
          {
            id: 'github',
            name: 'GIT-PLATFORM',
            command: 'docker',
            enabled: true,
            args: ['-e', 'GITHUB_PERSONAL_ACCESS_TOKEN'],
            envVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN'],
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
          GITHUB_PERSONAL_ACCESS_TOKEN: 'env-token-value',
          OTHER_VAR: 'other-value',
        })
      } finally {
        // Restore original env
        if (originalEnv !== undefined) {
          process.env.GITHUB_PERSONAL_ACCESS_TOKEN = originalEnv
        } else {
          delete process.env.GITHUB_PERSONAL_ACCESS_TOKEN
        }
      }
    })

    it('should not fall back to process.env for variables not listed in envVarNames', () => {
      // Set up process.env with a variable that's NOT in envVarNames
      const originalSomeOtherToken = process.env.SOME_OTHER_TOKEN
      const originalGithubToken = process.env.GITHUB_PERSONAL_ACCESS_TOKEN
      
      process.env.SOME_OTHER_TOKEN = 'should-not-be-used'
      delete process.env.GITHUB_PERSONAL_ACCESS_TOKEN // Clear this to ensure test isolation

      try {
        const codayServers: McpServerConfig[] = [
          {
            id: 'test',
            name: 'Test Server',
            command: 'test',
            enabled: true,
            envVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN'], // Only this one should be picked up, but it's not in process.env
            // No env specified
          },
        ]

        const result = mergeMcpConfigs(codayServers, [], [])

        // Should be empty because:
        // - SOME_OTHER_TOKEN is in process.env but not in envVarNames
        // - GITHUB_PERSONAL_ACCESS_TOKEN is in envVarNames but not in process.env
        expect(result[0]?.env).toEqual({})
      } finally {
        // Restore original env
        if (originalSomeOtherToken !== undefined) {
          process.env.SOME_OTHER_TOKEN = originalSomeOtherToken
        } else {
          delete process.env.SOME_OTHER_TOKEN
        }
        if (originalGithubToken !== undefined) {
          process.env.GITHUB_PERSONAL_ACCESS_TOKEN = originalGithubToken
        }
      }
    })

    it('should merge envVarNames from multiple levels', () => {
      const codayServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          command: 'test',
          enabled: true,
          envVarNames: ['VAR1', 'VAR2'],
        },
      ]

      const projectServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          envVarNames: ['VAR2', 'VAR3'], // VAR2 is duplicate
        },
      ]

      const userServers: McpServerConfig[] = [
        {
          id: 'test',
          name: 'Test Server',
          envVarNames: ['VAR4'],
        },
      ]

      const result = mergeMcpConfigs(codayServers, projectServers, userServers)

      // Should have unique values from all levels
      expect(result[0]?.envVarNames).toEqual(['VAR1', 'VAR2', 'VAR3', 'VAR4'])
    })

    it('should handle multiple environment variables in envVarNames', () => {
      // Set up process.env with multiple variables
      const originalToken = process.env.GITHUB_PERSONAL_ACCESS_TOKEN
      const originalApiKey = process.env.API_KEY
      const originalSecret = process.env.SECRET_TOKEN

      process.env.GITHUB_PERSONAL_ACCESS_TOKEN = 'github-token'
      process.env.API_KEY = 'api-key-value'
      process.env.SECRET_TOKEN = 'secret-value'

      try {
        const codayServers: McpServerConfig[] = [
          {
            id: 'test',
            name: 'Test Server',
            command: 'test',
            enabled: true,
            envVarNames: ['GITHUB_PERSONAL_ACCESS_TOKEN', 'API_KEY', 'SECRET_TOKEN'],
            // No env specified
          },
        ]

        const result = mergeMcpConfigs(codayServers, [], [])

        expect(result[0]?.env).toEqual({
          GITHUB_PERSONAL_ACCESS_TOKEN: 'github-token',
          API_KEY: 'api-key-value',
          SECRET_TOKEN: 'secret-value',
        })
      } finally {
        // Restore original env
        if (originalToken !== undefined) {
          process.env.GITHUB_PERSONAL_ACCESS_TOKEN = originalToken
        } else {
          delete process.env.GITHUB_PERSONAL_ACCESS_TOKEN
        }
        if (originalApiKey !== undefined) {
          process.env.API_KEY = originalApiKey
        } else {
          delete process.env.API_KEY
        }
        if (originalSecret !== undefined) {
          process.env.SECRET_TOKEN = originalSecret
        } else {
          delete process.env.SECRET_TOKEN
        }
      }
    })
  })
})

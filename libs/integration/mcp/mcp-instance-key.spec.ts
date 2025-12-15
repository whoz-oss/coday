import { computeMcpConfigHash } from './mcp-instance-key'
import { McpServerConfig } from '../../model/mcp-server-config'

describe('computeMcpConfigHash', () => {
  describe('identical configs produce same hash', () => {
    it('should produce same hash for identical command-based configs', () => {
      const config1: McpServerConfig = {
        id: 'test1',
        name: 'Test Server 1',
        command: 'npx',
        args: ['@modelcontextprotocol/server-filesystem', '/tmp'],
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test2', // Different id
        name: 'Test Server 2', // Different name
        command: 'npx',
        args: ['@modelcontextprotocol/server-filesystem', '/tmp'],
        enabled: false, // Different enabled
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })

    it('should produce same hash for identical url-based configs', () => {
      const config1: McpServerConfig = {
        id: 'remote1',
        name: 'Remote Server 1',
        url: 'https://example.com/mcp',
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'remote2',
        name: 'Remote Server 2',
        url: 'https://example.com/mcp',
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })
  })

  describe('order independence', () => {
    it('should produce same hash regardless of args order', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        args: ['arg1', 'arg2', 'arg3'],
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        args: ['arg3', 'arg1', 'arg2'], // Different order
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })

    it('should produce same hash regardless of env var order', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        env: {
          VAR_A: 'value_a',
          VAR_B: 'value_b',
          VAR_C: 'value_c',
        },
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        env: {
          VAR_C: 'value_c',
          VAR_A: 'value_a',
          VAR_B: 'value_b',
        },
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })
  })

  describe('different configs produce different hashes', () => {
    it('should produce different hash for different commands', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'npx',
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).not.toBe(hash2)
    })

    it('should produce different hash for different args', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        args: ['script1.js'],
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        args: ['script2.js'],
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).not.toBe(hash2)
    })

    it('should produce different hash for different env vars', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        env: { VAR: 'value1' },
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        env: { VAR: 'value2' },
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).not.toBe(hash2)
    })

    it('should produce different hash for different cwd', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        cwd: '/path1',
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        cwd: '/path2',
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).not.toBe(hash2)
    })

    it('should produce different hash for different debug mode', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        debug: false,
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        debug: true,
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).not.toBe(hash2)
    })
  })

  describe('noShare flag', () => {
    it('should produce unique hash when noShare is true', () => {
      const config: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        noShare: true,
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config)
      const hash2 = computeMcpConfigHash(config)

      // Each call should produce a different hash
      expect(hash1).not.toBe(hash2)
      expect(hash1).toMatch(/^no-share-/)
      expect(hash2).toMatch(/^no-share-/)
    })

    it('should produce normal hash when noShare is false', () => {
      const config: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        noShare: false,
        enabled: true,
      }

      const hash = computeMcpConfigHash(config)

      // Should be a normal SHA-256 hash (64 hex chars)
      expect(hash).toMatch(/^[0-9a-f]{64}$/)
      expect(hash).not.toMatch(/^no-share-/)
    })

    it('should produce normal hash when noShare is undefined', () => {
      const config: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        enabled: true,
      }

      const hash = computeMcpConfigHash(config)

      // Should be a normal SHA-256 hash (64 hex chars)
      expect(hash).toMatch(/^[0-9a-f]{64}$/)
    })
  })

  describe('metadata fields are ignored', () => {
    it('should ignore id and name differences', () => {
      const config1: McpServerConfig = {
        id: 'id1',
        name: 'Name 1',
        command: 'node',
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'id2',
        name: 'Name 2',
        command: 'node',
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })

    it('should ignore enabled differences', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        enabled: false,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })

    it('should ignore allowedTools differences', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        allowedTools: ['tool1', 'tool2'],
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        allowedTools: ['tool3'],
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })
  })

  describe('empty and undefined values', () => {
    it('should handle configs with no args', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        args: [],
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })

    it('should handle configs with no env', () => {
      const config1: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'test',
        name: 'Test',
        command: 'node',
        env: {},
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      expect(hash1).toBe(hash2)
    })
  })

  describe('real-world scenarios', () => {
    it('should handle filesystem MCP with different paths', () => {
      const config1: McpServerConfig = {
        id: 'fs1',
        name: 'Filesystem 1',
        command: 'npx',
        args: ['@modelcontextprotocol/server-filesystem', '/tmp'],
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'fs2',
        name: 'Filesystem 2',
        command: 'npx',
        args: ['@modelcontextprotocol/server-filesystem', '/home'],
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      // Different paths should produce different hashes
      expect(hash1).not.toBe(hash2)
    })

    it('should handle playwright MCP with different configurations', () => {
      const config1: McpServerConfig = {
        id: 'pw1',
        name: 'Playwright 1',
        command: 'npx',
        args: ['-y', '@modelcontextprotocol/server-playwright'],
        enabled: true,
      }

      const config2: McpServerConfig = {
        id: 'pw2',
        name: 'Playwright 2',
        command: 'npx',
        args: ['@modelcontextprotocol/server-playwright', '-y'], // Different order
        enabled: true,
      }

      const hash1 = computeMcpConfigHash(config1)
      const hash2 = computeMcpConfigHash(config2)

      // Same args but different order should produce same hash
      expect(hash1).toBe(hash2)
    })
  })
})

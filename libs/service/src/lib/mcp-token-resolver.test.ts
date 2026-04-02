import { McpServerConfig } from '@coday/model'
import { resolveTokensInString, resolveServerTokens } from './mcp-token-resolver'

describe('resolveTokensInString', () => {
  it('replaces {{projectRoot}} with the given path', () => {
    expect(resolveTokensInString('--workspacePath={{projectRoot}}', { projectRoot: '/home/user/my-project' })).toBe(
      '--workspacePath=/home/user/my-project'
    )
  })

  it('replaces multiple occurrences of {{projectRoot}}', () => {
    expect(resolveTokensInString('{{projectRoot}}/a:{{projectRoot}}/b', { projectRoot: '/root' })).toBe(
      '/root/a:/root/b'
    )
  })

  it('leaves strings without tokens unchanged', () => {
    expect(resolveTokensInString('--no-minimal', { projectRoot: '/root' })).toBe('--no-minimal')
  })

  it('leaves unknown tokens unchanged', () => {
    expect(resolveTokensInString('{{unknownToken}}', { projectRoot: '/root' })).toBe('{{unknownToken}}')
  })

  it('handles empty string', () => {
    expect(resolveTokensInString('', { projectRoot: '/root' })).toBe('')
  })
})

describe('resolveServerTokens', () => {
  const projectRoot = '/home/user/whoz'

  it('resolves {{projectRoot}} in args', () => {
    const server: McpServerConfig = {
      id: 'nx-mcp',
      name: 'NX',
      command: 'npx',
      args: ['nx', 'mcp', '--workspacePath={{projectRoot}}'],
      enabled: true,
    }
    const result = resolveServerTokens(server, projectRoot)
    expect(result.args).toEqual(['nx', 'mcp', `--workspacePath=${projectRoot}`])
  })

  it('resolves {{projectRoot}} in command', () => {
    const server: McpServerConfig = {
      id: 'test',
      name: 'Test',
      command: '{{projectRoot}}/bin/my-mcp',
      enabled: true,
    }
    const result = resolveServerTokens(server, projectRoot)
    expect(result.command).toBe(`${projectRoot}/bin/my-mcp`)
  })

  it('resolves {{projectRoot}} in cwd', () => {
    const server: McpServerConfig = {
      id: 'test',
      name: 'Test',
      command: 'some-mcp',
      cwd: '{{projectRoot}}',
      enabled: true,
    }
    const result = resolveServerTokens(server, projectRoot)
    expect(result.cwd).toBe(projectRoot)
  })

  it('resolves {{projectRoot}} in env values', () => {
    const server: McpServerConfig = {
      id: 'test',
      name: 'Test',
      command: 'some-mcp',
      env: { MY_PATH: '{{projectRoot}}/config', STATIC: 'no-token' },
      enabled: true,
    }
    const result = resolveServerTokens(server, projectRoot)
    expect(result.env).toEqual({
      MY_PATH: `${projectRoot}/config`,
      STATIC: 'no-token',
    })
  })

  it('does not mutate the original server config', () => {
    const server: McpServerConfig = {
      id: 'nx-mcp',
      name: 'NX',
      command: 'npx',
      args: ['nx', 'mcp', '--workspacePath={{projectRoot}}'],
      enabled: true,
    }
    const originalArgs = [...server.args!]
    resolveServerTokens(server, projectRoot)
    expect(server.args).toEqual(originalArgs)
  })

  it('handles server with no args, cwd, or env', () => {
    const server: McpServerConfig = {
      id: 'minimal',
      name: 'Minimal',
      command: 'minimal-mcp',
      enabled: true,
    }
    const result = resolveServerTokens(server, projectRoot)
    expect(result.args).toBeUndefined()
    expect(result.cwd).toBeUndefined()
    expect(result.env).toBeUndefined()
    expect(result.command).toBe('minimal-mcp')
  })

  it('handles undefined command', () => {
    const server: McpServerConfig = {
      id: 'url-based',
      name: 'URL Based',
      url: 'http://localhost:3000',
      enabled: true,
    }
    const result = resolveServerTokens(server, projectRoot)
    expect(result.command).toBeUndefined()
  })

  it('preserves all other server fields unchanged', () => {
    const server: McpServerConfig = {
      id: 'nx-mcp',
      name: 'NX',
      command: 'npx',
      args: ['nx', 'mcp'],
      enabled: true,
      debug: true,
      noShare: false,
      allowedTools: ['nx_docs'],
      authToken: 'secret',
    }
    const result = resolveServerTokens(server, projectRoot)
    expect(result.id).toBe('nx-mcp')
    expect(result.name).toBe('NX')
    expect(result.enabled).toBe(true)
    expect(result.debug).toBe(true)
    expect(result.noShare).toBe(false)
    expect(result.allowedTools).toEqual(['nx_docs'])
    expect(result.authToken).toBe('secret')
  })
})

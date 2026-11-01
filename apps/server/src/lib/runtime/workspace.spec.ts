import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import {
  buildForwardArgs,
  generateWorkspace,
  NX_PROJECT,
  NX_TASKS,
  shellQuote,
  taskCommand,
  writeWorkspace,
} from './workspace'

const input = {
  configDir: '/home/me/.coday',
  nodePath: '/usr/bin/node',
  serverJs: '/opt/coday/server.js',
  forwardArgs: ['--configDir', '/home/me/.coday'],
  platform: 'linux' as const,
}

describe('generateWorkspace', () => {
  const files = generateWorkspace(input)
  const nx = JSON.parse(files['nx.json'] as string)
  const project = JSON.parse(files['project.json'] as string)

  it('writes the three files of a minimal workspace', () => {
    expect(Object.keys(files).sort()).toEqual(['nx.json', 'package.json', 'project.json'])
    expect(JSON.parse(files['package.json'] as string).private).toBe(true)
  })

  it('runs without the daemon, the cloud or the cache', () => {
    expect(nx.useDaemonProcess).toBe(false)
    expect(nx.neverConnectToCloud).toBe(true)
    expect(nx.targetDefaults['*'].cache).toBe(false)
  })

  it('declares one project named coday with provision, server, agentos, factory and status', () => {
    expect(project.name).toBe(NX_PROJECT)
    expect(Object.keys(project.targets).sort()).toEqual(['agentos', 'factory', 'provision', 'server', 'status'])
    expect([...NX_TASKS].sort()).toEqual(['agentos', 'factory', 'server', 'status'])
  })

  it('makes provision a normal task and the four others continuous tasks depending on it', () => {
    expect(project.targets.provision.continuous).toBeUndefined()
    expect(project.targets.provision.dependsOn).toBeUndefined()
    for (const name of NX_TASKS) {
      expect(project.targets[name].continuous).toBe(true)
      expect(project.targets[name].dependsOn).toEqual(['provision'])
    }
  })

  it('runs the bundled server with the command and the forwarded arguments', () => {
    expect(project.targets.agentos.options.command).toBe(
      '"/usr/bin/node" "/opt/coday/server.js" "agentos" "--configDir" "/home/me/.coday"'
    )
    expect(project.targets.provision.options.command).toContain('"provision"')
    expect(project.targets.status.options.command).toBe(
      '"/usr/bin/node" "/opt/coday/server.js" "status" "--configDir" "/home/me/.coday"'
    )
  })

  it('is deterministic (no timestamp), so an unchanged workspace is never rewritten', () => {
    expect(generateWorkspace(input)).toEqual(files)
  })
})

describe('shellQuote / taskCommand', () => {
  it('keeps a path with spaces as one argument', () => {
    expect(taskCommand({ ...input, forwardArgs: ['--configDir', '/a b/.coday'] }, 'server')).toContain('"/a b/.coday"')
  })

  it('escapes shell metacharacters on POSIX', () => {
    expect(shellQuote('a"b$c`d\\e', 'linux')).toBe('"a\\"b\\$c\\`d\\\\e"')
  })

  it('doubles quotes on Windows', () => {
    expect(shellQuote('a"b', 'win32')).toBe('"a""b"')
  })
})

describe('writeWorkspace', () => {
  let configDir: string
  beforeEach(() => {
    configDir = fs.mkdtempSync(path.join(os.tmpdir(), 'workspace-'))
  })
  afterEach(() => fs.rmSync(configDir, { recursive: true, force: true }))

  it('writes under <configDir>/runtime and does not touch an unchanged file', () => {
    const dir = writeWorkspace({ ...input, configDir })
    expect(dir).toBe(path.join(configDir, 'runtime'))
    expect(fs.existsSync(path.join(dir, 'nx.json'))).toBe(true)

    const before = fs.statSync(path.join(dir, 'project.json')).mtimeMs
    writeWorkspace({ ...input, configDir })
    expect(fs.statSync(path.join(dir, 'project.json')).mtimeMs).toBe(before)
  })

  it('rewrites a file whose content changed (new JAR path after an upgrade)', () => {
    const dir = writeWorkspace({ ...input, configDir })
    writeWorkspace({ ...input, configDir, serverJs: '/opt/coday-2/server.js' })
    expect(fs.readFileSync(path.join(dir, 'project.json'), 'utf8')).toContain('/opt/coday-2/server.js')
  })
})

describe('buildForwardArgs', () => {
  it('drops the command word and pins --configDir when it was not given', () => {
    expect(buildForwardArgs(['workspace', '--auth'], '/c')).toEqual(['--auth', '--configDir', '/c'])
  })

  it('never forwards the launcher-only flags (--no-open, --managed) to the tasks', () => {
    expect(buildForwardArgs(['workspace', '--no-open', '--auth', '--managed'], '/c')).toEqual([
      '--auth',
      '--configDir',
      '/c',
    ])
  })

  it('keeps an explicit --configDir untouched', () => {
    expect(buildForwardArgs(['--configDir', '/x', '--host', '0.0.0.0'], '/x')).toEqual([
      '--configDir',
      '/x',
      '--host',
      '0.0.0.0',
    ])
  })
})

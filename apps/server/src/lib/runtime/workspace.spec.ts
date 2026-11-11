import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import {
  buildForwardArgs,
  generateWorkspace,
  NX_ENTRY_TASK,
  NX_PROJECT,
  NX_SERVICES,
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
  })

  it('makes provision a normal task and every other task a continuous one', () => {
    expect(project.targets.provision.continuous).toBeUndefined()
    expect(project.targets.provision.dependsOn).toBeUndefined()
    expect([...NX_TASKS].sort()).toEqual(['agentos', 'factory', 'server', 'status'])
    for (const name of NX_TASKS) expect(project.targets[name].continuous).toBe(true)
  })

  it('makes every service depend on provision only', () => {
    for (const name of NX_SERVICES) expect(project.targets[name].dependsOn).toEqual(['provision'])
  })

  it('makes status the entry task: it depends on provision and on every service, server FIRST', () => {
    // The Nx TUI pins the initiating task and its first continuous dependency: server must come first.
    expect(NX_ENTRY_TASK).toBe('status')
    expect(NX_SERVICES[0]).toBe('server')
    expect(project.targets[NX_ENTRY_TASK].dependsOn).toEqual(['provision', ...NX_SERVICES])
  })

  it('runs the bundled server with the command and the forwarded arguments', () => {
    expect(project.targets.agentos.options.command).toBe(
      '"/usr/bin/node" "/opt/coday/server.js" "agentos" "--configDir" "/home/me/.coday"'
    )
    expect(project.targets.provision.options.command).toContain('"provision"')
    expect(project.targets.status.options.command).toContain('"status"')
  })

  it('is run by the launcher as the single target coday:status (launcher.js is plain JS, it cannot import this)', () => {
    const launcher = fs.readFileSync(path.join(__dirname, '../../../../web/launcher.js'), 'utf8')
    expect(launcher).toContain(`['run', '${NX_PROJECT}:${NX_ENTRY_TASK}',`)
    expect(launcher).not.toContain("'run-many'")
  })

  it('is deterministic (no timestamp), so an unchanged workspace is never rewritten', () => {
    expect(generateWorkspace(input)).toEqual(files)
  })

  it('leaves the factory target out when it is disabled: no pane, and status no longer depends on it', () => {
    const without = JSON.parse(generateWorkspace({ ...input, withFactory: false })['project.json'] as string)
    expect(Object.keys(without.targets).sort()).toEqual(['agentos', 'provision', 'server', 'status'])
    // server stays first: it is the one pinned next to status in the TUI.
    expect(without.targets.status.dependsOn).toEqual(['provision', 'server', 'agentos'])
  })

  it('leaves the agentos target out for an external AgentOS, the factory one staying', () => {
    const without = JSON.parse(generateWorkspace({ ...input, withAgentos: false })['project.json'] as string)
    expect(Object.keys(without.targets).sort()).toEqual(['factory', 'provision', 'server', 'status'])
    expect(without.targets.status.dependsOn).toEqual(['provision', 'server', 'factory'])
  })

  it('leaves both service targets out when both are external: only server and status remain', () => {
    const without = JSON.parse(
      generateWorkspace({ ...input, withAgentos: false, withFactory: false })['project.json'] as string
    )
    expect(Object.keys(without.targets).sort()).toEqual(['provision', 'server', 'status'])
    expect(without.targets.status.dependsOn).toEqual(['provision', 'server'])
  })

  it('keeps the factory by default', () => {
    expect(
      JSON.parse(generateWorkspace({ ...input, withFactory: true })['project.json'] as string).targets.factory
    ).toBeDefined()
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

  it('forwards --no-open to every task (the server task reads it)', () => {
    expect(buildForwardArgs(['--no-open'], '/c')).toEqual(['--no-open', '--configDir', '/c'])
  })

  it('forwards an explicit --configDir once, after the other arguments', () => {
    expect(buildForwardArgs(['--configDir', '/x', '--host', '0.0.0.0'], '/x')).toEqual([
      '--host',
      '0.0.0.0',
      '--configDir',
      '/x',
    ])
  })

  it('makes a relative --configDir absolute: the tasks run from <configDir>/runtime, not from here', () => {
    const absolute = path.resolve('my-coday')
    expect(buildForwardArgs(['--configDir', './my-coday'], './my-coday')).toEqual(['--configDir', absolute])
    expect(buildForwardArgs(['--configDir=./my-coday', '--auth'], './my-coday')).toEqual([
      '--auth',
      '--configDir',
      absolute,
    ])
    expect(buildForwardArgs(['--coday_config_dir', './my-coday'], './my-coday')).toEqual(['--configDir', absolute])
  })
})

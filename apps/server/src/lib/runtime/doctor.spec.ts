import {
  buildReport,
  checkConfigDir,
  checkJava,
  checkNode,
  checkPorts,
  checkRipgrep,
  DoctorFacts,
  NODE_MIN_VERSION,
  parseNodeMajor,
  renderReport,
} from './doctor'

const okFacts = (): DoctorFacts => ({
  node: `${NODE_MIN_VERSION}.11.0`,
  java: { state: 'ok', version: 'openjdk 25 2025-09-16', major: 25 },
  configDir: { path: '/home/me/.coday', writable: true },
  ripgrep: 'ripgrep 14.1.0',
  ports: { express: 3000, base: 3000, notes: [] },
  platform: 'darwin',
})

describe('parseNodeMajor', () => {
  it.each([
    ['24.4.0', 24],
    ['v22.11.0', 22],
    ['22', 22],
  ])('reads %s', (version, major) => expect(parseNodeMajor(version)).toBe(major))

  it('gives nothing for a string that is not a version', () => {
    expect(parseNodeMajor('unknown')).toBeUndefined()
    expect(parseNodeMajor('')).toBeUndefined()
  })
})

describe('checkNode', () => {
  it('blocks below the minimum, with an install hint for the platform', () => {
    const check = checkNode('18.20.0', 'darwin')
    expect(check).toMatchObject({ state: 'blocked', detail: 'v18.20.0' })
    expect(check.hint).toContain('brew install node')
    expect(checkNode('18.20.0', 'win32').hint).toContain('winget')
  })

  it('passes from the minimum up', () => {
    expect(checkNode(`${NODE_MIN_VERSION}.0.0`).state).toBe('ok')
    expect(checkNode('24.4.0').state).toBe('ok')
  })

  it('only warns when the version cannot be read: it is not a reason to stop', () => {
    expect(checkNode('nightly').state).toBe('warn')
  })
})

describe('checkJava', () => {
  it('blocks on a missing JVM and keeps the install hint', () => {
    expect(checkJava({ state: 'missing', hint: 'brew install --cask temurin@25' })).toMatchObject({
      state: 'blocked',
      detail: 'not found',
      hint: 'brew install --cask temurin@25',
    })
  })

  it('blocks on a JVM that is too old and shows the version found', () => {
    expect(checkJava({ state: 'too-old', version: 'openjdk 21.0.3', major: 21, hint: 'upgrade' })).toMatchObject({
      state: 'blocked',
      detail: 'openjdk 21.0.3',
    })
  })

  it('passes and names the version', () => {
    expect(checkJava({ state: 'ok', version: 'openjdk 25 2025-09-16', major: 25 })).toMatchObject({
      state: 'ok',
      detail: 'openjdk 25 2025-09-16',
    })
  })
})

describe('checkConfigDir', () => {
  it('blocks when the directory cannot be written, and says why', () => {
    const check = checkConfigDir({
      path: '/ro/.coday',
      writable: false,
      error: "EACCES: permission denied, mkdir '/ro'",
    })
    expect(check.state).toBe('blocked')
    expect(check.detail).toContain('EACCES')
    expect(check.hint).toContain('--configDir')
  })

  it('passes and names the directory', () => {
    expect(checkConfigDir({ path: '/home/me/.coday', writable: true })).toMatchObject({
      state: 'ok',
      detail: '/home/me/.coday',
    })
  })
})

describe('checkRipgrep', () => {
  it('only warns when absent: Coday starts without it', () => {
    const check = checkRipgrep(undefined, 'darwin')
    expect(check.state).toBe('warn')
    expect(check.hint).toContain('brew install ripgrep')
  })

  it('passes with the version line', () => {
    expect(checkRipgrep('ripgrep 14.1.0')).toMatchObject({ state: 'ok', detail: 'ripgrep 14.1.0' })
  })
})

describe('checkPorts', () => {
  it('passes quietly on the first block and gives the address to open', () => {
    expect(checkPorts({ express: 3000, base: 3000, notes: [] })).toMatchObject({
      state: 'ok',
      detail: 'block 3000, open http://localhost:3000/',
    })
  })

  it('warns when the stack moved, so nobody opens the old instance on 3000', () => {
    const check = checkPorts({
      express: 3020,
      base: 3020,
      notes: ['Port 3000 is in use (by node, pid 42), Coday starts on block 3020: http://localhost:3020/'],
    })
    expect(check.state).toBe('warn')
    expect(check.detail).toContain('http://localhost:3020/')
    expect(check.hint).toContain('pid 42')
  })

  it('says so when the ports come from the OS', () => {
    expect(checkPorts({ express: 51234, notes: ['every block is taken'] }).detail).toContain('ports assigned by the OS')
  })

  it('blocks when an explicitly chosen port is taken', () => {
    expect(checkPorts({ notes: [], error: 'Port 3000 is already in use (by nginx, pid 7).' })).toMatchObject({
      state: 'blocked',
      detail: 'Port 3000 is already in use (by nginx, pid 7).',
    })
  })
})

describe('buildReport', () => {
  it('reports every check and is not blocked when all is well', () => {
    const report = buildReport(okFacts())
    expect(report.blocked).toBe(false)
    expect(report.checks.map((c) => c.name)).toEqual(['node', 'java', 'config', 'ripgrep', 'ports'])
  })

  it('is blocked as soon as one check is', () => {
    expect(buildReport({ ...okFacts(), java: { state: 'missing', hint: 'install java' } }).blocked).toBe(true)
  })

  it('is not blocked by a warning alone', () => {
    expect(buildReport({ ...okFacts(), ripgrep: undefined }).blocked).toBe(false)
  })
})

describe('renderReport', () => {
  it('prints one aligned line per check, the hint underneath', () => {
    const lines = renderReport(buildReport({ ...okFacts(), ripgrep: undefined }))
    expect(lines[0]).toBe('Coday preflight')
    expect(lines).toContain('  ok  node     v22.11.0')
    expect(lines.some((l) => l.includes('!!  ripgrep  not found'))).toBe(true)
    expect(lines.some((l) => l.trim().startsWith('Agents search files with it'))).toBe(true)
  })

  it('ends on what to do when the start is blocked, and not otherwise', () => {
    const blocked = renderReport(buildReport({ ...okFacts(), java: { state: 'missing', hint: 'install java' } }))
    expect(blocked[blocked.length - 1]).toContain('Coday cannot start')
    const fine = renderReport(buildReport(okFacts()))
    expect(fine.some((l) => l.includes('cannot start'))).toBe(false)
  })
})

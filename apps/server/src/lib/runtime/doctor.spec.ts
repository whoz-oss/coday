import {
  buildReport,
  checkConfigDir,
  checkJava,
  checkNode,
  checkPorts,
  checkRipgrep,
  DoctorFacts,
  JavaNeed,
  NODE_MIN_VERSION,
  parseNodeMajor,
  renderReport,
} from './doctor'

const okFacts = (): DoctorFacts => ({
  node: `${NODE_MIN_VERSION}.11.0`,
  java: { state: 'ok', version: 'openjdk 25 2025-09-16', major: 25 },
  javaNeed: { required: true, awaited: false },
  configDir: { path: '/home/me/.coday', writable: true },
  ripgrep: 'ripgrep 14.1.0',
  ports: { express: 3000, base: 3000 },
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
  const needed: JavaNeed = { required: true, awaited: false }

  it('blocks on a missing JVM and keeps the install hint', () => {
    expect(checkJava({ state: 'missing', hint: 'brew install --cask temurin@25' }, needed)).toMatchObject({
      state: 'blocked',
      detail: 'not found',
      hint: 'brew install --cask temurin@25',
    })
  })

  it('blocks on a JVM that is too old and shows the version found', () => {
    expect(
      checkJava({ state: 'too-old', version: 'openjdk 21.0.3', major: 21, hint: 'upgrade' }, needed)
    ).toMatchObject({ state: 'blocked', detail: 'openjdk 21.0.3' })
  })

  it('passes and names the version', () => {
    expect(checkJava({ state: 'ok', version: 'openjdk 25 2025-09-16', major: 25 }, needed)).toMatchObject({
      state: 'ok',
      detail: 'openjdk 25 2025-09-16',
    })
  })

  it('only warns on a first run: the wizard is reachable and waits for Java', () => {
    const check = checkJava({ state: 'missing', hint: 'install it' }, { required: true, awaited: true })
    expect(check.state).toBe('warn')
    expect(check.hint).toContain('waits for it')
  })

  it('passes when no JVM is managed at all (external AgentOS and Factory)', () => {
    expect(checkJava({ state: 'missing', hint: 'install it' }, { required: false, awaited: false })).toMatchObject({
      state: 'ok',
    })
  })

  it('says where JAVA_HOME went wrong instead of "not found"', () => {
    expect(
      checkJava({ state: 'missing', hint: 'unset it', alternative: { version: 'openjdk 25' } }, needed)
    ).toMatchObject({ detail: 'not where JAVA_HOME points' })
    expect(
      checkJava(
        { state: 'too-old', version: 'openjdk 17', major: 17, hint: 'h', alternative: { version: 'openjdk 25' } },
        needed
      )
    ).toMatchObject({ detail: 'openjdk 17 at JAVA_HOME' })
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
  it('passes quietly and gives the address to open', () => {
    expect(checkPorts({ express: 3000, base: 3000 })).toMatchObject({
      state: 'ok',
      detail: 'block 3000, open http://localhost:3000/',
    })
  })

  it('only warns when the ports could not be determined', () => {
    expect(checkPorts({})).toMatchObject({ state: 'warn', detail: 'could not be determined' })
  })

  it('blocks on a failed choice without guessing a cause: the message already says what to do', () => {
    const check = checkPorts({ error: 'Port 3000 is already in use (by nginx, pid 7).' })
    expect(check).toMatchObject({ state: 'blocked', detail: 'Port 3000 is already in use (by nginx, pid 7).' })
    expect(check.hint).toBeUndefined()
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

  it('is not blocked by a missing Java the first run will wait for', () => {
    const facts = {
      ...okFacts(),
      java: { state: 'missing' as const, hint: 'install java' },
      javaNeed: { required: true, awaited: true },
    }
    expect(buildReport(facts).blocked).toBe(false)
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

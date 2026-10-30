import * as crypto from 'crypto'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { ensureJarSet, jarFilePath, parseManifest, readInstalledSet, selectArtifacts } from './jar-set'

let configDir: string

beforeEach(() => {
  configDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jar-set-'))
})

afterEach(() => {
  fs.rmSync(configDir, { recursive: true, force: true })
})

function touch(file: string): void {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, 'jar')
}

const hash = (name: string) =>
  crypto
    .createHash('sha256')
    .update(Buffer.from(`bytes-of-${name}`))
    .digest('hex')

describe('parseManifest', () => {
  it('parses sha256sum lines and skips malformed ones', () => {
    const entries = parseManifest(`${hash('a')}  a.jar\n\nnot a line\nshort  b.jar\n${hash('c')}  c.jar\n`)
    expect(entries.map((e) => e.filename)).toEqual(['a.jar', 'c.jar'])
  })
})

describe('selectArtifacts', () => {
  const manifest = (names: string[]) => names.map((filename) => ({ hash: hash(filename), filename }))

  it('selects the service and every plugin of the kind at the version, whatever their name', () => {
    const selected = selectArtifacts(
      manifest([
        'agentos-service-3.36.1.jar',
        'agentos-bash-plugin-3.36.1.jar',
        'agentos-git-plugin-3.36.1.jar',
        'agentos-brand-new-plugin-3.36.1.jar',
      ]),
      'agentos',
      '3.36.1'
    )
    expect(selected.map((a) => a.artifactId)).toEqual([
      'agentos-service',
      'agentos-bash-plugin',
      'agentos-git-plugin',
      'agentos-brand-new-plugin',
    ])
  })

  it('ignores shared libs, other versions and the other platform', () => {
    const selected = selectArtifacts(
      manifest([
        'agentos-service-3.36.1.jar',
        'agentos-git-3.36.1.jar', // shared lib, not a plugin
        'agentos-bash-plugin-3.36.0.jar', // another version
        'factory-service-3.36.1.jar', // other platform (combined workstream manifest)
        'factory-forge-plugin-3.36.1.jar',
      ]),
      'agentos',
      '3.36.1'
    )
    expect(selected.map((a) => a.artifactId)).toEqual(['agentos-service'])
  })

  it('supports workstream versions containing dots and hyphens', () => {
    const version = '0.0.0-workstream-forge.34'
    const selected = selectArtifacts(
      manifest([`factory-service-${version}.jar`, `factory-forge-plugin-${version}.jar`]),
      'factory',
      version
    )
    expect(selected.map((a) => a.artifactId)).toEqual(['factory-service', 'factory-forge-plugin'])
  })

  it('throws when the release has no service for the version', () => {
    expect(() => selectArtifacts(manifest(['agentos-bash-plugin-3.36.1.jar']), 'agentos', '3.36.1')).toThrow(
      /agentos-service/
    )
  })
})

describe('readInstalledSet', () => {
  const install = (version: string, ids: string[], kind: 'agentos' | 'factory' = 'agentos') => {
    for (const id of ids) touch(jarFilePath(configDir, kind, id, version))
    fs.writeFileSync(
      path.join(configDir, kind, 'installed.sha256'),
      ids.map((id) => `${hash(id)}  ${id}-${version}.jar`).join('\n') + '\n'
    )
  }

  it('is null on a first run', () => {
    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).toBeNull()
  })

  it('returns the installed artifact ids when complete', () => {
    install('3.36.1', ['agentos-service', 'agentos-bash-plugin'])
    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).toEqual(['agentos-service', 'agentos-bash-plugin'])
  })

  it('is null when the marker is for another version', () => {
    install('3.36.0', ['agentos-service', 'agentos-bash-plugin'])
    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).toBeNull()
  })

  it('is null when a listed JAR is missing', () => {
    install('3.36.1', ['agentos-service', 'agentos-bash-plugin'])
    fs.unlinkSync(jarFilePath(configDir, 'agentos', 'agentos-bash-plugin', '3.36.1'))
    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).toBeNull()
  })

  it('is null when the marker does not list the service', () => {
    install('3.36.1', ['agentos-bash-plugin'])
    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).toBeNull()
  })
})

describe('ensureJarSet', () => {
  const originalFetch = global.fetch
  afterEach(() => {
    global.fetch = originalFetch
  })

  /** Mock a release; `names` are the manifest files, the body of each is `bytes-of-<name>`. */
  function mockRelease(names: string[]): string[] {
    const manifest = names.map((n) => `${hash(n)}  ${n}`).join('\n')
    const requested: string[] = []
    global.fetch = (async (input: string | URL | Request) => {
      const url = String(input)
      requested.push(url)
      const name = url.split('/').pop() as string
      if (name === 'checksums.sha256') return new Response(manifest)
      return names.includes(name)
        ? new Response(new Uint8Array(Buffer.from(`bytes-of-${name}`)))
        : new Response('nope', { status: 404 })
    }) as typeof fetch
    return requested
  }

  const agentosRelease = (v: string) => [
    `agentos-service-${v}.jar`,
    `agentos-bash-plugin-${v}.jar`,
    `agentos-git-plugin-${v}.jar`,
    `agentos-datetime-plugin-${v}.jar`,
    `agentos-git-${v}.jar`, // shared lib: must be ignored
  ]

  it('installs every plugin the release provides, with no list in the code (git-plugin included)', async () => {
    mockRelease(agentosRelease('3.36.1'))

    const result = await ensureJarSet(configDir, 'agentos', '3.36.1')

    expect(result.outcome).toBe('success')
    expect(result.downloaded).toBe(4)
    for (const id of ['agentos-service', 'agentos-bash-plugin', 'agentos-git-plugin', 'agentos-datetime-plugin']) {
      expect(fs.existsSync(jarFilePath(configDir, 'agentos', id, '3.36.1'))).toBe(true)
    }
    expect(fs.existsSync(path.join(configDir, 'agentos', 'agentos-git-3.36.1.jar'))).toBe(false)
    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).toHaveLength(4)
  })

  it('skips without any network call once the set is complete', async () => {
    mockRelease(agentosRelease('3.36.1'))
    await ensureJarSet(configDir, 'agentos', '3.36.1')

    const requested = mockRelease([])
    const result = await ensureJarSet(configDir, 'agentos', '3.36.1')

    expect(result.outcome).toBe('skipped:all-present')
    expect(requested).toEqual([])
  })

  it('installs the requested version over a cached older one and removes the older one', async () => {
    mockRelease(agentosRelease('3.36.0'))
    await ensureJarSet(configDir, 'agentos', '3.36.0')

    mockRelease(agentosRelease('3.36.1'))
    expect((await ensureJarSet(configDir, 'agentos', '3.36.1')).outcome).toBe('success')

    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).not.toBeNull()
    expect(readInstalledSet(configDir, 'agentos', '3.36.0')).toBeNull()
    expect(fs.readdirSync(path.join(configDir, 'agentos')).filter((f) => f.endsWith('.jar'))).toEqual([
      'agentos-service-3.36.1.jar',
    ])
    expect(fs.readdirSync(path.join(configDir, 'agentos', 'plugins')).every((f) => f.includes('3.36.1'))).toBe(true)
  })

  it('picks up a plugin added to the release after a previous install of the same version', async () => {
    // Installed by an older release layout: the marker lists no git-plugin, a plugin is gone from disk.
    mockRelease(agentosRelease('3.36.1'))
    await ensureJarSet(configDir, 'agentos', '3.36.1')
    fs.unlinkSync(jarFilePath(configDir, 'agentos', 'agentos-git-plugin', '3.36.1'))

    const requested = mockRelease(agentosRelease('3.36.1'))
    expect((await ensureJarSet(configDir, 'agentos', '3.36.1')).outcome).toBe('success')

    expect(requested.length).toBeGreaterThan(0)
    expect(fs.existsSync(jarFilePath(configDir, 'agentos', 'agentos-git-plugin', '3.36.1'))).toBe(true)
  })

  it('treats an interrupted download as incomplete', async () => {
    mockRelease(agentosRelease('3.36.1'))
    await ensureJarSet(configDir, 'agentos', '3.36.1')

    // Next download dies on a plugin: the marker must not survive.
    const names = agentosRelease('3.36.1')
    const manifest = names.map((n) => `${hash(n)}  ${n}`).join('\n')
    fs.unlinkSync(jarFilePath(configDir, 'agentos', 'agentos-bash-plugin', '3.36.1'))
    global.fetch = (async (input: string | URL | Request) => {
      const name = String(input).split('/').pop() as string
      if (name === 'checksums.sha256') return new Response(manifest)
      return name.startsWith('agentos-bash') ? new Response('boom', { status: 500 }) : new Response('x')
    }) as typeof fetch
    expect((await ensureJarSet(configDir, 'agentos', '3.36.1')).outcome).toBe('error:download-failed')

    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).toBeNull()
  })

  it('fails on a checksum mismatch and does not commit', async () => {
    mockRelease(agentosRelease('3.36.1'))
    const manifest = agentosRelease('3.36.1')
      .map((n) => `${n === 'agentos-bash-plugin-3.36.1.jar' ? 'a'.repeat(64) : hash(n)}  ${n}`)
      .join('\n')
    const real = global.fetch
    global.fetch = (async (input: string | URL | Request) =>
      String(input).endsWith('checksums.sha256') ? new Response(manifest) : real(input)) as typeof fetch

    const result = await ensureJarSet(configDir, 'agentos', '3.36.1')

    expect(result.outcome).toBe('error:download-failed')
    expect(result.message).toContain('Checksum mismatch')
    expect(readInstalledSet(configDir, 'agentos', '3.36.1')).toBeNull()
  })

  it('reports a failed manifest fetch and keeps the cached older version untouched', async () => {
    mockRelease(agentosRelease('3.36.0'))
    await ensureJarSet(configDir, 'agentos', '3.36.0')

    global.fetch = (async () => new Response('nope', { status: 404 })) as typeof fetch
    const result = await ensureJarSet(configDir, 'agentos', '3.36.1')

    expect(result.outcome).toBe('error:download-failed')
    expect(result.message).toContain('Manifest HTTP 404')
    expect(fs.existsSync(jarFilePath(configDir, 'agentos', 'agentos-service', '3.36.0'))).toBe(true)
  })

  it('removes a leftover next/ directory from the former background pre-fetch flow', async () => {
    touch(path.join(configDir, 'factory', 'next', 'factory-service-3.36.1.jar'))
    mockRelease(['factory-service-3.36.1.jar', 'factory-forge-plugin-3.36.1.jar'])

    expect((await ensureJarSet(configDir, 'factory', '3.36.1')).outcome).toBe('success')

    expect(fs.existsSync(path.join(configDir, 'factory', 'next'))).toBe(false)
  })

  it('keeps agentos and factory apart in a combined workstream manifest and downloads from its tag', async () => {
    const version = '0.0.0-workstream-forge.34'
    const requested = mockRelease([
      ...agentosRelease(version),
      `factory-service-${version}.jar`,
      `factory-forge-plugin-${version}.jar`,
    ])

    expect((await ensureJarSet(configDir, 'factory', version)).downloaded).toBe(2)

    expect(requested[0]).toBe(
      'https://github.com/whoz-oss/coday/releases/download/workstream-forge-34/checksums.sha256'
    )
    expect(fs.existsSync(path.join(configDir, 'agentos'))).toBe(false)
  })

  it("skips the 'dev' sentinel, using a hand-placed JAR when there is one", async () => {
    expect((await ensureJarSet(configDir, 'agentos', 'dev')).outcome).toBe('skipped:dev-version')

    touch(jarFilePath(configDir, 'agentos', 'agentos-service', 'dev'))
    expect((await ensureJarSet(configDir, 'agentos', 'dev')).outcome).toBe('skipped:all-present')
  })
})

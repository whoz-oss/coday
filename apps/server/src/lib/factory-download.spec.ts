import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import {
  checkAndSwapNextFactory,
  downloadFactoryJars,
  downloadFactoryJarsToNext,
  factoryJarPath,
  findInstalledFactoryVersion,
  findMissingFactoryJars,
} from './factory-download'

let configDir: string

function touch(file: string): void {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, 'jar')
}

function installActive(
  version: string,
  artifacts: Array<'factory-service' | 'factory-forge-plugin'> = ['factory-service', 'factory-forge-plugin']
): void {
  for (const id of artifacts) touch(factoryJarPath(configDir, id, version))
}

function installNext(
  version: string,
  artifacts: Array<'factory-service' | 'factory-forge-plugin'> = ['factory-service', 'factory-forge-plugin']
): void {
  const next = path.join(configDir, 'factory', 'next')
  if (artifacts.includes('factory-service')) touch(path.join(next, `factory-service-${version}.jar`))
  if (artifacts.includes('factory-forge-plugin'))
    touch(path.join(next, 'plugins', `factory-forge-plugin-${version}.jar`))
}

beforeEach(() => {
  configDir = fs.mkdtempSync(path.join(os.tmpdir(), 'factory-download-'))
})

afterEach(() => {
  fs.rmSync(configDir, { recursive: true, force: true })
})

describe('findInstalledFactoryVersion', () => {
  it('returns null on a first run', () => {
    expect(findInstalledFactoryVersion(configDir)).toBeNull()
  })

  it('returns the version of a coherent set', () => {
    installActive('3.36.1')
    expect(findInstalledFactoryVersion(configDir)).toBe('3.36.1')
  })

  it('supports workstream versions containing dots and hyphens', () => {
    installActive('0.0.0-workstream-forge.34')
    expect(findInstalledFactoryVersion(configDir)).toBe('0.0.0-workstream-forge.34')
  })

  it('returns null for a partial set', () => {
    installActive('3.36.1', ['factory-service'])
    expect(findInstalledFactoryVersion(configDir)).toBeNull()
  })

  it('returns null when artifacts are at different versions', () => {
    installActive('3.36.0', ['factory-service'])
    installActive('3.36.1', ['factory-forge-plugin'])
    expect(findInstalledFactoryVersion(configDir)).toBeNull()
  })

  it('ignores the next/ directory', () => {
    installNext('3.36.1')
    expect(findInstalledFactoryVersion(configDir)).toBeNull()
  })
})

describe('findMissingFactoryJars', () => {
  it('lists artifacts absent at the expected version', () => {
    installActive('3.36.0')
    expect(findMissingFactoryJars(configDir, '3.36.1')).toEqual(['factory-service', 'factory-forge-plugin'])
  })
})

describe('checkAndSwapNextFactory', () => {
  it('does nothing without a next/ directory', () => {
    expect(checkAndSwapNextFactory(configDir, '3.36.1').outcome).toBe('skipped:no-next')
  })

  it('does nothing when next/ is incomplete', () => {
    installActive('3.36.0')
    installNext('3.36.1', ['factory-service'])

    expect(checkAndSwapNextFactory(configDir, '3.36.1').outcome).toBe('skipped:incomplete')
    expect(findInstalledFactoryVersion(configDir)).toBe('3.36.0')
  })

  it('does nothing when next/ holds another version than the target', () => {
    installActive('3.36.0')
    installNext('3.35.0')

    expect(checkAndSwapNextFactory(configDir, '3.36.1').outcome).toBe('skipped:incomplete')
  })

  it('promotes a complete next/ and removes the previous version and next/', () => {
    installActive('3.36.0')
    installNext('3.36.1')

    expect(checkAndSwapNextFactory(configDir, '3.36.1').outcome).toBe('swapped')

    expect(findMissingFactoryJars(configDir, '3.36.1')).toEqual([])
    expect(findMissingFactoryJars(configDir, '3.36.0')).toEqual(['factory-service', 'factory-forge-plugin'])
    expect(fs.existsSync(path.join(configDir, 'factory', 'next'))).toBe(false)
  })
})

describe('download entry points that need no network', () => {
  it('downloadFactoryJars skips when everything is present', async () => {
    installActive('3.36.1')
    expect((await downloadFactoryJars(configDir, '3.36.1')).outcome).toBe('skipped:all-present')
  })

  it("downloadFactoryJars skips the 'dev' sentinel", async () => {
    expect((await downloadFactoryJars(configDir, 'dev')).outcome).toBe('skipped:dev-version')
  })

  it("downloadFactoryJarsToNext skips the 'dev' sentinel", async () => {
    expect((await downloadFactoryJarsToNext(configDir, 'dev')).outcome).toBe('skipped:dev-version')
  })

  it('downloadFactoryJarsToNext skips when next/ is already complete', async () => {
    installNext('3.36.1')
    expect((await downloadFactoryJarsToNext(configDir, '3.36.1')).outcome).toBe('skipped:already-in-next')
  })
})

describe('downloads with a mocked network', () => {
  const originalFetch = global.fetch
  afterEach(() => {
    global.fetch = originalFetch
  })

  function mockRelease(version: string): string[] {
    const crypto = require('crypto') as typeof import('crypto')
    const bodies: Record<string, Buffer> = {
      [`factory-service-${version}.jar`]: Buffer.from('service-bytes'),
      [`factory-forge-plugin-${version}.jar`]: Buffer.from('plugin-bytes'),
    }
    const manifest = Object.entries(bodies)
      .map(([name, buf]) => `${crypto.createHash('sha256').update(buf).digest('hex')}  ${name}`)
      .join('\n')
    const requested: string[] = []
    global.fetch = (async (input: string | URL | Request) => {
      const url = String(input)
      requested.push(url)
      const name = url.split('/').pop() as string
      if (name === 'checksums.sha256') return new Response(manifest)
      const body = bodies[name]
      return body ? new Response(new Uint8Array(body)) : new Response('nope', { status: 404 })
    }) as typeof fetch
    return requested
  }

  it('downloadFactoryJarsToNext fills next/ and leaves the active set untouched', async () => {
    installActive('3.36.0')
    mockRelease('3.36.1')

    expect((await downloadFactoryJarsToNext(configDir, '3.36.1')).outcome).toBe('success')

    expect(findInstalledFactoryVersion(configDir)).toBe('3.36.0')
    expect(fs.existsSync(path.join(configDir, 'factory', 'next', 'factory-service-3.36.1.jar'))).toBe(true)
    expect(fs.existsSync(path.join(configDir, 'factory', 'next', 'plugins', 'factory-forge-plugin-3.36.1.jar'))).toBe(
      true
    )
  })

  it('pre-fetch then swap yields the upgraded active set (full upgrade flow)', async () => {
    installActive('3.36.0')
    mockRelease('3.36.1')

    await downloadFactoryJarsToNext(configDir, '3.36.1')
    expect(checkAndSwapNextFactory(configDir, '3.36.1').outcome).toBe('swapped')

    expect(findInstalledFactoryVersion(configDir)).toBe('3.36.1')
  })

  it('downloadFactoryJars installs into the active layout and removes stale versions', async () => {
    installActive('3.36.0')
    mockRelease('3.36.1')

    expect((await downloadFactoryJars(configDir, '3.36.1')).outcome).toBe('success')

    expect(findInstalledFactoryVersion(configDir)).toBe('3.36.1')
    expect(findMissingFactoryJars(configDir, '3.36.0').length).toBe(2)
  })

  it('downloads a workstream version from its workstream tag', async () => {
    const version = '0.0.0-workstream-forge.34'
    const requested = mockRelease(version)

    expect((await downloadFactoryJars(configDir, version)).outcome).toBe('success')

    expect(requested[0]).toBe(
      'https://github.com/whoz-oss/coday/releases/download/workstream-forge-34/checksums.sha256'
    )
  })

  it('reports a failed download and leaves next/ unpublished by a swap', async () => {
    global.fetch = (async () => new Response('nope', { status: 404 })) as typeof fetch

    const result = await downloadFactoryJarsToNext(configDir, '3.36.1')

    expect(result.outcome).toBe('error:download-failed')
    expect(result.message).toContain('Manifest HTTP 404')
    expect(checkAndSwapNextFactory(configDir, '3.36.1').outcome).not.toBe('swapped')
  })
})

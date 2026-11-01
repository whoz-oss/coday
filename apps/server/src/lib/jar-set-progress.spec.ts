import * as crypto from 'crypto'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { DownloadProgress, ensureJarSet, overallPercent } from './jar-set'

const hash = (name: string) =>
  crypto
    .createHash('sha256')
    .update(Buffer.from(`bytes-of-${name}`))
    .digest('hex')

describe('overallPercent', () => {
  it('weights artifacts by size', () => {
    expect(overallPercent([90, 10], 0, 0.5)).toBe(45)
    expect(overallPercent([90, 10], 1, 0)).toBe(90)
    expect(overallPercent([90, 10], 2, 0)).toBe(100)
  })

  it('clamps and tolerates empty input', () => {
    expect(overallPercent([], 0, 0)).toBe(0)
    expect(overallPercent([10], 0, 5)).toBe(100)
    expect(overallPercent([10], 0, -1)).toBe(0)
  })
})

describe('ensureJarSet progress', () => {
  const originalFetch = global.fetch
  let configDir: string
  beforeEach(() => {
    configDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jar-progress-'))
  })
  afterEach(() => {
    global.fetch = originalFetch
    fs.rmSync(configDir, { recursive: true, force: true })
  })

  const names = ['agentos-service-9.9.9.jar', 'agentos-bash-plugin-9.9.9.jar']
  const manifest = names.map((n) => `${hash(n)}  ${n}`).join('\n')

  function mockRelease(): void {
    global.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
      const name = String(input).split('/').pop() as string
      if (name === 'checksums.sha256') return new Response(manifest)
      const body = Buffer.from(`bytes-of-${name}`)
      const headers = { 'content-length': String(body.length) }
      if (init?.method === 'HEAD') return new Response(null, { headers })
      // Two chunks so the byte counter has something to report.
      const half = Math.ceil(body.length / 2)
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new Uint8Array(body.subarray(0, half)))
          controller.enqueue(new Uint8Array(body.subarray(half)))
          controller.close()
        },
      })
      return new Response(stream, { headers })
    }) as typeof fetch
  }

  it('reports monotonic progress ending at 100% and still installs the set', async () => {
    mockRelease()
    const events: DownloadProgress[] = []

    const result = await ensureJarSet(configDir, 'agentos', '9.9.9', (p) => events.push(p))

    expect(result.outcome).toBe('success')
    expect(events.length).toBeGreaterThan(2)
    const percents = events.map((e) => e.overallPercent)
    expect([...percents].sort((a, b) => a - b)).toEqual(percents)
    expect(percents[percents.length - 1]).toBe(100)
    expect(events.some((e) => e.totalBytes && e.receivedBytes > 0 && e.receivedBytes < e.totalBytes)).toBe(true)
    expect(new Set(events.map((e) => e.artifactId))).toEqual(new Set(['agentos-service', 'agentos-bash-plugin']))
  })

  it('works exactly as before without a listener', async () => {
    mockRelease()
    expect((await ensureJarSet(configDir, 'agentos', '9.9.9')).outcome).toBe('success')
  })
})

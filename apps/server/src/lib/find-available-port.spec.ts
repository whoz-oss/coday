import net from 'node:net'
import {
  buildPortInUseMessage,
  describePortInUse,
  findAvailablePort,
  findPortOwner,
  parseLsofOwner,
} from './find-available-port'

const listenOnLoopback = (): Promise<{ server: net.Server; port: number }> =>
  new Promise((resolve) => {
    const server = net.createServer()
    server.listen(0, '127.0.0.1', () => resolve({ server, port: (server.address() as net.AddressInfo).port }))
  })

const close = (server: net.Server): Promise<void> => new Promise((resolve) => server.close(() => resolve()))

describe('findAvailablePort', () => {
  it('returns the start port when nothing listens on it', async () => {
    const { server, port } = await listenOnLoopback()
    await close(server)
    expect(await findAvailablePort(port, 0, true)).toBe(port)
  })

  it('sees a port held on 127.0.0.1 only (the coday-web case), not just on the wildcard', async () => {
    const { server, port } = await listenOnLoopback()
    try {
      await expect(findAvailablePort(port, 0, true)).rejects.toThrow(`Port ${port} is already in use`)
      expect(await findAvailablePort(port, 10)).toBeGreaterThan(port)
    } finally {
      await close(server)
    }
  })

  it('gives up after the allowed attempts when not strict', async () => {
    const { server, port } = await listenOnLoopback()
    try {
      await expect(findAvailablePort(port, 0)).rejects.toThrow('Could not find an available port')
    } finally {
      await close(server)
    }
  })

  it('no longer names a product that does not exist anymore', async () => {
    const { server, port } = await listenOnLoopback()
    try {
      let message = ''
      try {
        await findAvailablePort(port, 0, true)
      } catch (error) {
        message = error instanceof Error ? error.message : String(error)
      }
      expect(message).toMatch(/^Port \d+ is already in use/)
      expect(message).not.toMatch(/CodayTwin|restart/i)
    } finally {
      await close(server)
    }
  })
})

describe('buildPortInUseMessage', () => {
  it('names the port and what to do, on one line', () => {
    const message = buildPortInUseMessage(3000, undefined, 'linux')
    expect(message).toMatch(/^Port 3000 is already in use\./)
    expect(message).toContain('PORT=<n>')
    expect(message).not.toContain('\n')
  })

  it('names the process holding the port when known', () => {
    expect(buildPortInUseMessage(3000, 'node, pid 4242', 'linux')).toMatch(
      /^Port 3000 is already in use \(by node, pid 4242\)\./
    )
  })

  it.each([5000, 7000])('hints at the AirPlay Receiver for %i on macOS', (port) => {
    const message = buildPortInUseMessage(port, 'ControlCenter, pid 612', 'darwin')
    expect(message).toContain('AirPlay Receiver')
    expect(message).toContain('ControlCenter, pid 612')
  })

  it('gives the AirPlay hint on macOS only, and for those ports only', () => {
    expect(buildPortInUseMessage(5000, undefined, 'linux')).not.toContain('AirPlay')
    expect(buildPortInUseMessage(5000, undefined, 'win32')).not.toContain('AirPlay')
    expect(buildPortInUseMessage(3000, undefined, 'darwin')).not.toContain('AirPlay')
  })

  it('keeps the shape the desktop apps parse (server-lifecycle.ts)', () => {
    const line = `PORT_IN_USE: ${buildPortInUseMessage(3049, 'node, pid 1', 'darwin')}`
    expect(line.match(/PORT_IN_USE:.*?(Port \d+ is already in use[^\n]*)/)?.[1]).toContain(
      'Port 3049 is already in use'
    )
  })
})

describe('describePortInUse', () => {
  it('looks the owner up through the injected lookup', async () => {
    const asked: number[] = []
    const message = await describePortInUse(3000, async (port) => (asked.push(port), 'node, pid 7'), 'linux')
    expect(asked).toEqual([3000])
    expect(message).toContain('(by node, pid 7)')
  })

  it('never throws when the lookup does: the message just has no owner', async () => {
    const message = await describePortInUse(
      3000,
      async () => {
        throw new Error('lsof: not found')
      },
      'linux'
    )
    expect(message).toMatch(/^Port 3000 is already in use\./)
    expect(message).not.toContain('(by')
  })
})

describe('parseLsofOwner', () => {
  it('reads the first process of lsof -Fpc output', () => {
    expect(parseLsofOwner('p4242\ncnode\n')).toBe('node, pid 4242')
    expect(parseLsofOwner('p612\ncControlCenter\np613\ncother\n')).toBe('ControlCenter, pid 612')
  })

  it('handles a missing command and empty output', () => {
    expect(parseLsofOwner('p99\n')).toBe('pid 99')
    expect(parseLsofOwner('')).toBeUndefined()
  })
})

describe('findPortOwner', () => {
  it('is undefined on Windows, without trying anything', async () => {
    expect(await findPortOwner(3000, 'win32')).toBeUndefined()
  })

  it('never throws, whatever lsof says (best effort)', async () => {
    await expect(findPortOwner(1)).resolves.toBeUndefined()
  })
})

import net from 'node:net'
import { findAvailablePort } from './find-available-port'

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
})

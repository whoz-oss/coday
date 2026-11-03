import * as http from 'http'
import { AddressInfo } from 'net'
import { closeServer } from './http-server'

const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

describe('closeServer', () => {
  it('returns while a streamed response is still open, like a proxied event stream', async () => {
    const server = http.createServer((_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' })
      res.write('data: hello\n\n') // never ended
    })
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    const { port } = server.address() as AddressInfo
    const response = await fetch(`http://127.0.0.1:${port}/events`)
    const reader = (response.body as ReadableStream<Uint8Array>).getReader()
    await reader.read()

    const outcome = await Promise.race([closeServer(server).then(() => 'closed'), delay(2_000).then(() => 'hung')])
    server.closeAllConnections() // never leave the stream open after a failure

    expect(outcome).toBe('closed')
    expect(server.listening).toBe(false)
    await reader.cancel().catch(() => undefined)
  })
})

import { debugLog } from './log'

export async function findAvailablePort(startPort: number, maxAttempts = 10): Promise<number> {
  const net = await import('node:net')

  return new Promise((resolve, reject) => {
    function checkPort(port: number, attempts: number) {
      const server = net.createServer()

      server.listen(port, () => {
        server.close(() => {
          debugLog('PORT', `Port ${port} is available`)
          resolve(port)
        })
      })

      server.on('error', (err: NodeJS.ErrnoException) => {
        if (err.code === 'EADDRINUSE') {
          if (attempts > 0) {
            debugLog('PORT', `Port ${port} is in use, trying next`)
            checkPort(port + 1, attempts - 1)
          } else {
            reject(new Error(`Could not find an available port starting from ${startPort}`))
          }
        } else {
          reject(err)
        }
      })
    }

    checkPort(startPort, maxAttempts)
  })
}

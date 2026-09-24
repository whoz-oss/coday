import type { ShutdownController } from '../application/shutdown.js'

export interface ProcessPort {
  once(event: 'SIGTERM', listener: () => void): unknown
  exit(code: number): never
}

export function installSigtermHandler(controller: ShutdownController, processPort: ProcessPort = process): void {
  processPort.once('SIGTERM', () => {
    void controller.handle('SIGTERM')
  })
}

export function processExit(processPort: Pick<ProcessPort, 'exit'> = process): (code: number) => void {
  return (code) => processPort.exit(code)
}

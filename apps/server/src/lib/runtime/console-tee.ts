import * as fs from 'fs'
import * as path from 'path'
import { format } from 'util'

/**
 * Duplicate everything written through console.log/warn/error into a log file (appended), keeping the
 * normal console output. Used by the server so its debug stream also lands in `<configDir>/logs/server.log`.
 */
export function teeConsoleToFile(file: string): () => void {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const fd = fs.openSync(file, 'a')
  const originals = { log: console.log, warn: console.warn, error: console.error }
  for (const method of ['log', 'warn', 'error'] as const) {
    const original = originals[method].bind(console)
    console[method] = (...args: unknown[]) => {
      original(...args)
      try {
        fs.writeSync(fd, format(...args) + '\n')
      } catch {
        /* never let logging break the server */
      }
    }
  }
  return () => {
    console.log = originals.log
    console.warn = originals.warn
    console.error = originals.error
    try {
      fs.closeSync(fd)
    } catch {
      /* already closed */
    }
  }
}

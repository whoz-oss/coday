import * as fs from 'fs'
import * as path from 'path'
import { format } from 'util'

let sink: fs.WriteStream | null = null

/**
 * Mirror every debugLog line into a file (e.g. `<configDir>/logs/server.log`). Pass null to stop.
 * Console output is unchanged, the file is an additional sink.
 */
export function setLogFile(file: string | null): void {
  if (sink) {
    sink.end()
    sink = null
  }
  if (!file) return
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true })
    sink = fs.createWriteStream(file, { flags: 'a' })
    sink.on('error', () => {
      sink = null
    })
  } catch {
    sink = null
  }
}

/** Append a raw line to the log file only (no console output). */
export function logToFile(line: string): void {
  sink?.write(`${line}\n`)
}

export function debugLog(context: string, ...args: any[]) {
  const prefix = `${new Date().toISOString()} ${context}`
  console.log(prefix, ...args)
  sink?.write(`${format(prefix, ...args)}\n`)
}

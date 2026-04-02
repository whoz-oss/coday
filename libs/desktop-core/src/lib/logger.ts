import type { App } from 'electron'
import type { WriteFileOptions } from 'fs'

const fs = require('fs') as typeof import('fs')

let logFilePath: string | null = null
let isPackaged = false

/**
 * Initialize the log file. Must be called once at startup.
 * @param app - Electron App instance (passed to avoid a direct electron import in the lib)
 * @param logFileName - File name for the log (e.g. 'coday-desktop.log')
 */
export function initLogger(app: App, logFileName: string): void {
  isPackaged = app.isPackaged
  if (!isPackaged) return

  const resolvedPath = require('path').join(app.getPath('userData'), logFileName) as string
  logFilePath = resolvedPath
  try {
    const header = `\n\n${'='.repeat(80)}\nLog - Started at ${new Date().toISOString()}\n${'='.repeat(80)}\n`
    fs.appendFileSync(resolvedPath, header, 'utf8' as WriteFileOptions)
    log('INFO', 'Log file initialized at:', resolvedPath)
  } catch (error) {
    console.error('Failed to initialize log file:', error)
  }
}

/**
 * Enhanced logging that writes to both console and the log file (when packaged).
 */
export function log(level: 'INFO' | 'ERROR' | 'WARN', ...args: any[]): void {
  const timestamp = new Date().toISOString()
  const message = args.map((arg) => (typeof arg === 'object' ? JSON.stringify(arg, null, 2) : String(arg))).join(' ')
  const logLine = `[${timestamp}] [${level}] ${message}\n`

  if (level === 'ERROR') {
    console.error(...args)
  } else if (level === 'WARN') {
    console.warn(...args)
  } else {
    console.log(...args)
  }

  if (isPackaged && logFilePath) {
    try {
      fs.appendFileSync(logFilePath, logLine, 'utf8' as WriteFileOptions)
    } catch (error) {
      console.error('Failed to write to log file:', error)
    }
  }
}

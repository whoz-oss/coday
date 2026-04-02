import type { ChildProcess } from 'child_process'
import { log } from './logger'

const { spawn } = require('child_process') as typeof import('child_process')

export interface ServerConfig {
  /** Human-readable app name used in error messages */
  appName: string
  /** Fixed port to listen on */
  port: string
  /** Resolved path to the npx binary */
  npxPath: string
  /** Arguments after npx itself (e.g. ['--yes', '@whoz-oss/coday-web', '--multi']) */
  serverArgs: string[]
  /** Working directory for the npx process (use app.getPath('temp')) */
  npxCwd: string
  /** Environment variables for the child process */
  env: NodeJS.ProcessEnv
}

/**
 * Spawn the Coday web server via npx.
 * Resolves once the server prints its "Server is running on" line.
 * Rejects on process error, unexpected exit, or PORT_IN_USE.
 */
export async function startCodayServer(
  config: ServerConfig
): Promise<{ serverUrl: string; serverProcess: ChildProcess }> {
  const { appName, port, npxPath, serverArgs, npxCwd, env: baseEnv } = config

  const env = { ...baseEnv, PORT: port }
  log('INFO', `Starting ${appName} server on port ${port}...`)
  log('INFO', 'Spawning:', npxPath, serverArgs.join(' '))

  const serverProcess = spawn(npxPath, serverArgs, {
    stdio: ['ignore', 'pipe', 'pipe'],
    env,
    cwd: npxCwd,
    shell: false,
  })

  return new Promise<{ serverUrl: string; serverProcess: ChildProcess }>((resolve, reject) => {
    let serverReady = false
    let settled = false
    let detectedUrl = ''

    const settle = (fn: () => void) => {
      if (!settled) {
        settled = true
        fn()
      }
    }

    if (serverProcess.stdout) {
      serverProcess.stdout.on('data', (data: Buffer) => {
        const output = data.toString()
        log('INFO', '[Server]:', output)

        const match = output.match(/Server is running on (http:\/\/localhost:\d+)/)
        if (match) {
          detectedUrl = match[1]!
          log('INFO', 'Detected server URL:', detectedUrl)
          if (!serverReady) {
            serverReady = true
            // Give the server a moment to fully initialize
            setTimeout(() => settle(() => resolve({ serverUrl: detectedUrl, serverProcess })), 500)
          }
        }
      })
    }

    if (serverProcess.stderr) {
      serverProcess.stderr.on('data', (data: Buffer) => {
        const text = data.toString()
        log('ERROR', '[Server Error]:', text)

        if (text.includes('PORT_IN_USE:')) {
          const portMatch = text.match(/PORT_IN_USE:.*?(Port \d+ is already in use[^\n]*)/)
          const detail = portMatch ? portMatch[1] : `Port ${port} is already in use.`
          const portError: any = new Error(
            `${detail}\n\nPlease close any other application using this port and restart ${appName}.`
          )
          portError.userFacing = true
          settle(() => reject(portError))
        }
      })
    }

    serverProcess.on('error', (error: Error) => {
      log('ERROR', 'Failed to start server:', error)
      const wrappedError: any = new Error(`Failed to start ${appName} server: ${error.message}`)
      wrappedError.userFacing = true
      settle(() => reject(wrappedError))
    })

    serverProcess.on('exit', (code: number | null) => {
      log('INFO', 'Server process exited with code:', code)
      if (!serverReady) {
        const error: any = new Error(`Server process exited unexpectedly with code ${code}.\n\nCheck logs for details.`)
        error.userFacing = true
        settle(() => reject(error))
      }
    })
  })
}

/**
 * Kill the server process if it is running.
 */
export function stopCodayServer(serverProcess: ChildProcess | null): void {
  if (serverProcess) {
    log('INFO', 'Stopping server...')
    serverProcess.kill()
  }
}

/**
 * Check whether the server at the given URL is responding on /api/health.
 */
export async function isServerResponsive(serverUrl: string | null): Promise<boolean> {
  if (!serverUrl) return false
  try {
    const http = require('http') as typeof import('http')
    const url = new URL(serverUrl)
    return new Promise<boolean>((resolve) => {
      const req = http.get({ hostname: url.hostname, port: url.port, path: '/api/health', timeout: 2000 }, (res) => {
        resolve(res.statusCode === 200 || res.statusCode === 404)
      })
      req.on('error', () => resolve(false))
      req.on('timeout', () => {
        req.destroy()
        resolve(false)
      })
    })
  } catch (error) {
    log('ERROR', 'Error checking server responsiveness:', error)
    return false
  }
}

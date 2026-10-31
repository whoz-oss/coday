/**
 * Supervision of one managed JVM service: spawn, tee its output to a log file and to a sink
 * (an Nx pane or the server's own stdout), wait for health, stop gracefully.
 *
 * NO SIDE EFFECTS AT IMPORT TIME.
 */

import { ChildProcess, spawn } from 'child_process'
import * as fs from 'fs'
import * as path from 'path'
import { logFilePath } from './paths'
import { ServiceSpec } from './services'

const GRACEFUL_SHUTDOWN_TIMEOUT_MS = 10_000

export interface LogSink {
  write(line: string): void
  close(): void
}

/** Append every line to `<configDir>/logs/<name>.log` and hand it to `echo` (stdout, pane...). */
export function createLogSink(configDir: string, name: string, echo: (line: string) => void): LogSink {
  const file = logFilePath(configDir, name)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const fd = fs.openSync(file, 'a')
  let closed = false
  return {
    write(line) {
      echo(line)
      if (!closed) fs.writeSync(fd, line + '\n')
    },
    close() {
      if (closed) return
      closed = true
      fs.closeSync(fd)
    },
  }
}

export interface RunningService {
  spec: ServiceSpec
  child: ChildProcess
  /** Resolves when the process is gone (never rejects; spawn errors resolve with code null). */
  exited: Promise<{ code: number | null; signal: NodeJS.Signals | null; error?: string }>
  isDead(): boolean
  /** SIGTERM, then SIGKILL after 10 s. Resolves once the process is gone. */
  stop(): Promise<void>
}

function pipeLines(stream: NodeJS.ReadableStream | null, sink: LogSink): void {
  if (!stream) return
  let pending = ''
  stream.on('data', (data: Buffer) => {
    pending += data.toString()
    const lines = pending.split('\n')
    pending = lines.pop() ?? ''
    for (const line of lines) if (line.trim()) sink.write(line.trimEnd())
  })
  stream.on('end', () => {
    if (pending.trim()) sink.write(pending.trimEnd())
  })
}

export function spawnService(spec: ServiceSpec, sink: LogSink, spawnFn: typeof spawn = spawn): RunningService {
  sink.write(`[runtime] ${spec.bin} ${spec.args.join(' ')}   (cwd ${spec.cwd})`)
  const child = spawnFn(spec.bin, spec.args, {
    cwd: spec.cwd,
    env: spec.env,
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: false,
  })
  pipeLines(child.stdout, sink)
  pipeLines(child.stderr, sink)

  let dead = false
  const exited = new Promise<{ code: number | null; signal: NodeJS.Signals | null; error?: string }>((resolve) => {
    child.once('error', (err) => {
      dead = true
      sink.write(`[runtime] spawn error: ${err.message}`)
      resolve({ code: null, signal: null, error: err.message })
    })
    child.once('exit', (code, signal) => {
      dead = true
      sink.write(`[runtime] process exited code=${code} signal=${signal}`)
      resolve({ code, signal })
    })
  })

  // Last-resort: never leave a JVM behind if this Node process disappears.
  const onExit = () => {
    if (!dead) {
      try {
        child.kill('SIGKILL')
      } catch {
        /* already gone */
      }
    }
  }
  process.on('exit', onExit)
  void exited.then(() => process.removeListener('exit', onExit))

  const stop = async (): Promise<void> => {
    if (dead) return
    const timer = setTimeout(() => {
      try {
        child.kill('SIGKILL')
      } catch {
        /* ignore */
      }
    }, GRACEFUL_SHUTDOWN_TIMEOUT_MS)
    try {
      child.kill('SIGTERM')
    } catch {
      clearTimeout(timer)
      return
    }
    await exited
    clearTimeout(timer)
  }

  return { spec, child, exited, isDead: () => dead, stop }
}

export interface WaitOptions {
  timeoutMs: number
  intervalMs?: number
  isDead?: () => boolean
  fetchFn?: typeof fetch
  sleep?: (ms: number) => Promise<void>
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/** Poll `url` until it answers 2xx, the deadline passes or `isDead()` turns true. */
export async function waitForHealthy(url: string, options: WaitOptions): Promise<boolean> {
  const { timeoutMs, intervalMs = 2_000, isDead = () => false, fetchFn = fetch, sleep = defaultSleep } = options
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline && !isDead()) {
    try {
      const res = await fetchFn(url, { signal: AbortSignal.timeout(3_000) })
      if (res.ok) return true
    } catch {
      /* still starting */
    }
    await sleep(intervalMs)
  }
  return false
}

/** One non-blocking probe, for the status endpoint. */
export async function isHealthy(url: string, fetchFn: typeof fetch = fetch): Promise<boolean> {
  try {
    const res = await fetchFn(url, { signal: AbortSignal.timeout(1_500) })
    return res.ok
  } catch {
    return false
  }
}

/** Poll `predicate` until it returns a value (not undefined/false/null) or the signal aborts. */
export async function waitUntil<T>(
  predicate: () => T | undefined | false | null | Promise<T | undefined | false | null>,
  options: { intervalMs?: number; signal?: AbortSignal; sleep?: (ms: number) => Promise<void> } = {}
): Promise<T | undefined> {
  const { intervalMs = 1_000, signal, sleep = defaultSleep } = options
  while (!signal?.aborted) {
    const value = await predicate()
    if (value) return value
    await sleep(intervalMs)
  }
  return undefined
}

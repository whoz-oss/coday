/**
 * The entry points of the bundled server, selected by the first positional argument:
 *
 *   coday-server provision   fast local provisioning (profile, secrets, Java, ports) -> state.json   [Nx task]
 *   coday-server agentos     download, wait for first-run identity, run AgentOS in the foreground     [Nx task]
 *   coday-server factory     wait for AgentOS, download, run the Factory in the foreground            [Nx task]
 *   coday-server server      Express only; ports come from state.json                                [Nx task]
 *   workspace                write the generated Nx workspace under <configDir>/runtime/ (launcher only)
 *   (no command) | managed   Express + AgentOS + Factory in ONE process (desktop apps, no TTY)
 *
 * All the real logic lives in provision.ts / orchestrator.ts / web-server.ts; this file is glue.
 */

import { Server } from 'http'
import { CodayOptions, parseCodayOptions } from '../coday-options-utils'
import { findAvailablePort } from '../find-available-port'
import { debugLog } from '../log'
import { createWebApp } from '../web-server'
import { teeConsoleToFile } from './console-tee'
import {
  OrchestratorContext,
  provisionJars,
  startAgentosService,
  startFactoryService,
  StartedService,
  waitForAgentos,
} from './orchestrator'
import { logFilePath } from './paths'
import { createLogSink } from './process'
import { buildForwardArgs, writeWorkspace } from './workspace'
import { Command } from './command-names'
import { hideBin } from 'yargs/helpers'
import { decideProfile } from './profile'
import { provision } from './provision'
import { readState } from './state'

export { COMMANDS, parseCommand } from './command-names'

/** Honour a forced PORT (strict), otherwise fall back to the next free ports. */
async function chooseExpressPort(): Promise<number> {
  const preferred = process.env.PORT
    ? parseInt(process.env.PORT)
    : process.env.BUILD_ENV === 'development'
      ? 4100
      : 3000
  const forced = !!process.env.PORT
  try {
    return await findAvailablePort(preferred, forced ? 0 : 10, forced)
  } catch (error) {
    if (error instanceof Error && error.message.includes('already in use')) {
      // Parsed by the desktop apps (server-lifecycle.ts).
      process.stderr.write(`PORT_IN_USE: ${error.message}\n`)
    }
    throw error
  }
}

function listen(app: ReturnType<typeof createWebApp>, options: CodayOptions, port: number): Promise<Server> {
  return new Promise((resolve, reject) => {
    const server = app.listen(port, options.host, () => {
      // Parsed by the desktop apps and by the launcher: keep this exact line.
      console.log(`Server is running on http://localhost:${port}`)
      resolve(server)
    })
    server.on('error', reject)
  })
}

const welcomeUrlOf = (state: { ports: { express?: number } }): string =>
  `http://localhost:${state.ports.express ?? 3000}/welcome`

function profileInput(options: CodayOptions) {
  return {
    auth: options.auth,
    agentosUrl: process.env.AGENTOS_URL,
    factoryUrl: process.env.FACTORY_URL,
    externalUserId: process.env.AGENTOS_EXTERNAL_USERID,
    configDir: options.configDir,
  }
}

// ---------------------------------------------------------------------------
// Nx tasks
// ---------------------------------------------------------------------------

export async function runProvision(): Promise<number> {
  const options = parseCodayOptions()
  const expressPort = await chooseExpressPort()
  await provision({
    ...profileInput(options),
    orchestrator: 'nx',
    expressPort,
    log: (m) => console.log(`[provision] ${m}`),
  })
  console.log('[provision] done')
  return 0
}

/** Keep the process in the foreground until `stop` is called by a termination signal. */
function foreground(stop: () => Promise<void>): { signal: AbortSignal; stopped: Promise<void> } {
  const controller = new AbortController()
  let resolveStopped!: () => void
  const stopped = new Promise<void>((resolve) => (resolveStopped = resolve))
  let received = 0
  const handler = (name: string) => () => {
    received++
    if (received > 2) process.exit(1)
    if (received > 1) return // child cascade of the same Ctrl+C
    console.log(`Received ${name}, stopping...`)
    controller.abort()
    void stop().finally(resolveStopped)
  }
  for (const sig of ['SIGTERM', 'SIGINT', 'SIGHUP'] as const) process.on(sig, handler(sig))
  return { signal: controller.signal, stopped }
}

function taskContext(options: CodayOptions, name: string, signal?: AbortSignal): OrchestratorContext {
  return {
    configDir: options.configDir,
    baseEnv: process.env,
    sink: createLogSink(options.configDir, name, (line) => console.log(line)),
    signal,
    welcomeUrl: welcomeUrlOf,
  }
}

export async function runAgentosTask(): Promise<number> {
  const options = parseCodayOptions()
  let service: StartedService | null = null
  const { signal, stopped } = foreground(async () => void (await service?.shutdown()))
  const ctx = taskContext(options, 'agentos', signal)
  if (process.env.AGENTOS_URL) {
    ctx.sink.write('[agentos] External AgentOS configured (AGENTOS_URL): nothing to run.')
    return 0
  }
  service = await startAgentosService(ctx)
  if (!service) return signal.aborted ? 0 : 1
  if (service.running) {
    const exit = await Promise.race([service.running.exited, stopped.then(() => null)])
    return exit && exit.code ? exit.code : 0
  }
  await stopped // adopted instance: stay up like a service, nothing to supervise
  return 0
}

export async function runFactoryTask(): Promise<number> {
  const options = parseCodayOptions()
  let service: StartedService | null = null
  const { signal, stopped } = foreground(async () => void (await service?.shutdown()))
  const ctx = taskContext(options, 'factory', signal)
  if (process.env.FACTORY_URL) {
    ctx.sink.write('[factory] External Factory configured (FACTORY_URL): nothing to run.')
    return 0
  }
  // Download while AgentOS is still coming up: the wizard shows the progress.
  const download = provisionJars(ctx, 'factory')
  ctx.sink.write('[factory] Waiting for AgentOS to be healthy...')
  const agentosUrl = process.env.AGENTOS_URL ?? (await waitForAgentos(options.configDir, signal))
  if (!agentosUrl) return 0
  service = await startFactoryService(ctx, agentosUrl, download)
  if (!service?.running) return signal.aborted ? 0 : 1
  const exit = await Promise.race([service.running.exited, stopped.then(() => null)])
  return exit && exit.code ? exit.code : 0
}

export async function runServerTask(): Promise<number> {
  const options = parseCodayOptions()
  teeConsoleToFile(logFilePath(options.configDir, 'server'))
  const state = readState(options.configDir)
  const port = state?.ports.express
  if (!state?.provisioned || !port) {
    console.error('Runtime is not provisioned: run the provision task first (coday-server provision).')
    return 1
  }
  const decision = decideProfile(profileInput(options))
  const app = createWebApp({ codayOptions: options, setupEnabled: decision.setupEnabled && state.profile === 'laptop' })
  const server = await listen(app, options, port)
  const { stopped } = foreground(async () => void (await new Promise<void>((r) => server.close(() => r()))))
  await stopped
  return 0
}

// ---------------------------------------------------------------------------
// In-process mode (desktop apps, no TTY, CODAY_NO_NX=1)
// ---------------------------------------------------------------------------

export async function runManaged(): Promise<number> {
  const options = parseCodayOptions()
  teeConsoleToFile(logFilePath(options.configDir, 'server'))
  debugLog('INIT', 'Coday options:', options)

  const expressPort = await chooseExpressPort()
  const { decision } = await provision({
    ...profileInput(options),
    orchestrator: 'in-process',
    expressPort,
    log: (m) => debugLog('PROVISION', m),
  })
  const app = createWebApp({ codayOptions: options, setupEnabled: decision.setupEnabled })
  const server = await listen(app, options, expressPort)

  const services: StartedService[] = []
  const shutdown = async (): Promise<void> => {
    await new Promise<void>((r) => server.close(() => r()))
    // Factory first (it depends on AgentOS); only spawned processes are stopped, adopted ones are left alone.
    for (const service of [...services].reverse()) if (service.spawned) await service.shutdown()
  }
  const { signal, stopped } = foreground(shutdown)

  // Fire-and-forget: the server is up, the wizard (or the proxy errors) shows the progress.
  void (async () => {
    const sinkFor = (name: string) =>
      createLogSink(options.configDir, name, (line) =>
        // Straight to stdout: the console tee would duplicate it into server.log.
        process.stdout.write(`${new Date().toISOString()} ${name.toUpperCase()} ${line}\n`)
      )
    const base = { configDir: options.configDir, baseEnv: process.env, signal, welcomeUrl: welcomeUrlOf }
    const agentosCtx: OrchestratorContext = { ...base, sink: sinkFor('agentos') }
    const factoryCtx: OrchestratorContext = { ...base, sink: sinkFor('factory') }

    const factoryDownload = process.env.FACTORY_URL ? undefined : provisionJars(factoryCtx, 'factory')
    let agentosUrl = process.env.AGENTOS_URL
    if (!agentosUrl) {
      const agentos = await startAgentosService(agentosCtx)
      if (!agentos) return
      services.push(agentos)
      agentosUrl = `http://127.0.0.1:${agentos.port}`
    }
    if (process.env.FACTORY_URL) return
    const factory = await startFactoryService(factoryCtx, agentosUrl, factoryDownload)
    if (factory) services.push(factory)
  })().catch((error) => debugLog('RUNTIME', 'Unexpected error while starting the services (non-fatal):', error))

  await stopped // lives until a termination signal
  return 0
}

/** Write the Nx workspace and print `CODAY_RUNTIME_WORKSPACE=<dir>` on stdout (the launcher reads that line). */
export function runWorkspace(): number {
  const raw = hideBin(process.argv)
  const options = parseCodayOptions(raw)
  const dir = writeWorkspace({
    configDir: options.configDir,
    nodePath: process.execPath,
    serverJs: process.argv[1] ?? '',
    forwardArgs: buildForwardArgs(raw, options.configDir),
  })
  // Marker line: the launcher picks it out of whatever else the process prints on stdout.
  process.stdout.write(`CODAY_RUNTIME_WORKSPACE=${dir}\n`)
  return 0
}

export async function runCommand(command: Command | undefined): Promise<number> {
  switch (command) {
    case 'workspace':
      return runWorkspace()
    case 'provision':
      return runProvision()
    case 'agentos':
      return runAgentosTask()
    case 'factory':
      return runFactoryTask()
    case 'server':
      return runServerTask()
    default:
      return runManaged()
  }
}

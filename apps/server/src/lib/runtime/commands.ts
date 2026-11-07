/**
 * The entry points of the bundled server, selected by the first positional argument:
 *
 *   coday-server provision   fast local provisioning (profile, secrets, Java, ports) -> state.json   [Nx task]
 *   coday-server agentos     download, wait for first-run identity, run AgentOS in the foreground     [Nx task]
 *   coday-server factory     wait for AgentOS, download, run the Factory in the foreground            [Nx task]
 *   coday-server server      Express only; ports come from state.json, opens the browser             [Nx task]
 *   coday-server status      small pane: download %, Java, health, the URL to open                   [Nx task]
 *   workspace                write the generated Nx workspace under <configDir>/runtime/ (launcher only)
 *   (no command) | managed   Express + AgentOS + Factory in ONE process (desktop apps, no TTY)
 *
 * All the real logic lives in provision.ts / orchestrator.ts / web-server.ts; this file is glue.
 */

import { Server } from 'http'
import { CodayOptions, parseCodayOptions } from '../coday-options-utils'
import { debugLog } from '../log'
import { createWebApp } from '../web-server'
import { teeConsoleToFile } from './console-tee'
import { closeServer } from './http-server'
import {
  OrchestratorContext,
  provisionJars,
  startAgentosService,
  startFactoryService,
  waitForAgentos,
} from './orchestrator'
import { logFilePath } from './paths'
import { createLogSink, isHealthy, RunningService } from './process'
import { LOOPBACK } from './services'
import { buildForwardArgs, writeWorkspace } from './workspace'
import { agentosHealthUrl, openBrowser, planBrowserOpen } from './open-browser'
import { isManagedFactoryDisabled } from './factory-option'
import { createStatusReporter } from './status'
import { readWebSetup } from './web-setup'
import { Command } from './command-names'
import { hideBin } from 'yargs/helpers'
import { decideProfile } from './profile'
import { provision } from './provision'
import { chooseStackPorts, PickedPorts, stackRequestFromEnv, StackChoice } from './ports'
import { parsePortBase, portsFor, profileBase } from './port-scheme'
import { startupLines } from './startup-info'
import { readState } from './state'

export { COMMANDS, parseCommand } from './command-names'

/**
 * The ports of the stack, one block for all of it (see ports.ts). Only an explicit choice is strict: `--port` / `PORT`
 * (Express) and `CODAY_PORT_BASE` (the block); a packaged stack otherwise moves to the next free block of its profile
 * (prod 3000 -> 3020 -> 3040, local 3010 -> 3030 -> 3050) and says so. The dev profile is strict too: its Angular and
 * Nx configs are static and cannot follow a move.
 */
async function chooseStack(options: CodayOptions): Promise<StackChoice> {
  const state = readState(options.configDir)
  const request = stackRequestFromEnv(process.env, {
    port: options.port,
    remembered: parsePortBase(String(state?.portBlock ?? '')),
    managed: {
      agentos: !process.env.AGENTOS_URL,
      factory: !process.env.FACTORY_URL && !isManagedFactoryDisabled(),
    },
  })
  try {
    return await chooseStackPorts({ ...request, log: (m) => console.log(`[ports] ${m}`) })
  } catch (error) {
    if (error instanceof Error && error.message.includes('is already in use')) {
      // Parsed by the desktop apps (server-lifecycle.ts).
      process.stderr.write(`PORT_IN_USE: ${error.message}\n`)
    }
    throw error
  }
}

/**
 * The addresses of a start, printed once Express listens: Express is the only one to open, the other ports are
 * internal (see startup-info.ts).
 */
function printStartup(
  ports: Pick<PickedPorts, 'express'> & Partial<PickedPorts>,
  env: NodeJS.ProcessEnv = process.env
): void {
  const dev = env['BUILD_ENV'] === 'development'
  const block = portsFor(profileBase(env))
  const lines = startupLines({
    dev,
    express: ports.express,
    agentos: ports.agentos,
    factory: ports.factory,
    bolt: ports.agentosBolt,
    client: Number(env['ANGULAR_CLIENT_PORT']) || block.client,
    cockpit: Number(env['FACTORY_COCKPIT_PORT']) || block.factoryCockpit,
    externalAgentos: env['AGENTOS_URL'],
    externalFactory: env['FACTORY_URL'],
    factoryDisabled: isManagedFactoryDisabled(env),
  })
  for (const line of lines) console.log(line)
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

/** Where the wizard is, for the "waiting for first-run setup" message (always /welcome, whatever the setup state). */
const welcomeUrlOf = (state: { ports: { express?: number } }): string =>
  `http://localhost:${state.ports.express ?? portsFor(profileBase()).server}/welcome`

function profileInput(options: CodayOptions) {
  return {
    auth: options.auth,
    agentosUrl: process.env.AGENTOS_URL,
    factoryUrl: process.env.FACTORY_URL,
    externalUserId: process.env.AGENTOS_EXTERNAL_USERID,
    forcedProfile: options.profile,
    configDir: options.configDir,
  }
}

// ---------------------------------------------------------------------------
// Nx tasks
// ---------------------------------------------------------------------------

export async function runProvision(): Promise<number> {
  const options = parseCodayOptions()
  const choice = await chooseStack(options)
  await provision({
    ...profileInput(options),
    orchestrator: 'nx',
    ports: choice.ports,
    portBlock: choice.base,
    log: (m) => console.log(`[provision] ${m}`),
  })
  console.log('[provision] done')
  return 0
}

/**
 * A service task that cannot do its job says so and STAYS in the foreground until the termination signal, instead of
 * exiting. The launcher runs `nx run coday:status`: Nx marks a continuous dependency that exits while `status` is
 * unfinished as crashed, and a dependency that fails before `status` has started would SKIP `status` (no pane at
 * all). The reason is already in the pane and in the log; exit code 0 on signal.
 */
async function stayUpAfterFailure(
  name: string,
  reason: string,
  say: (line: string) => void,
  stopped: Promise<void>
): Promise<number> {
  say(`[${name}] ${reason} This pane stays open until you stop Coday (Ctrl+C); the other panes keep running.`)
  await stopped
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

function taskContext(
  options: CodayOptions,
  name: string,
  signal: AbortSignal,
  onSpawn: (running: RunningService) => void
): OrchestratorContext {
  return {
    configDir: options.configDir,
    baseEnv: process.env,
    sink: createLogSink(options.configDir, name, (line) => console.log(line)),
    signal,
    onSpawn,
    welcomeUrl: welcomeUrlOf,
  }
}

export async function runAgentosTask(): Promise<number> {
  const options = parseCodayOptions()
  // Set at spawn, before the health wait: a stop during that wait must reach the JVM too.
  let spawned: RunningService | undefined
  const { signal, stopped } = foreground(async () => void (await spawned?.stop()))
  const ctx = taskContext(options, 'agentos', signal, (running) => (spawned = running))
  if (process.env.AGENTOS_URL) {
    ctx.sink.write('[agentos] External AgentOS configured (AGENTOS_URL): nothing to run.')
    await stopped // stay up: an exit would read as a crash of a dependency of `status`
    return 0
  }
  const service = await startAgentosService(ctx)
  if (!service) {
    if (signal.aborted) return 0
    const say = (line: string) => ctx.sink.write(line)
    return stayUpAfterFailure('agentos', 'AgentOS could not be started (see above).', say, stopped)
  }
  if (service.running) {
    const exit = await Promise.race([service.running.exited, stopped.then(() => null)])
    return exit && exit.code ? exit.code : 0
  }
  await stopped // adopted instance: stay up like a service, nothing to supervise
  return 0
}

export async function runFactoryTask(): Promise<number> {
  const options = parseCodayOptions()
  let spawned: RunningService | undefined
  const { signal, stopped } = foreground(async () => void (await spawned?.stop()))
  const ctx = taskContext(options, 'factory', signal, (running) => (spawned = running))
  if (process.env.FACTORY_URL) {
    ctx.sink.write('[factory] External Factory configured (FACTORY_URL): nothing to run.')
    await stopped // stay up: an exit would read as a crash of a dependency of `status`
    return 0
  }
  // Download while AgentOS is still coming up: the wizard shows the progress.
  const download = provisionJars(ctx, 'factory')
  ctx.sink.write('[factory] Waiting for AgentOS to be healthy...')
  const agentosUrl = process.env.AGENTOS_URL ?? (await waitForAgentos(options.configDir, signal))
  if (!agentosUrl) return 0 // only when the signal aborted the wait
  const service = await startFactoryService(ctx, agentosUrl, download)
  if (!service?.running) {
    if (signal.aborted) return 0
    const say = (line: string) => ctx.sink.write(line)
    return stayUpAfterFailure('factory', 'The Factory could not be started (see above).', say, stopped)
  }
  const exit = await Promise.race([service.running.exited, stopped.then(() => null)])
  return exit && exit.code ? exit.code : 0
}

export async function runServerTask(): Promise<number> {
  const options = parseCodayOptions()
  teeConsoleToFile(logFilePath(options.configDir, 'server'))
  let server: Server | undefined
  const { stopped } = foreground(async () => {
    if (server) await closeServer(server)
  })
  const say = (line: string) => console.error(line)
  const state = readState(options.configDir)
  const port = state?.ports.express
  if (!state?.provisioned || !port) {
    return stayUpAfterFailure(
      'server',
      'Runtime is not provisioned: run the provision task first (coday-server provision).',
      say,
      stopped
    )
  }
  const decision = decideProfile(profileInput(options))
  try {
    const app = createWebApp({
      codayOptions: options,
      setupEnabled: decision.setupEnabled && state.profile === 'laptop',
    })
    server = await listen(app, options, port)
    printStartup({ ...state.ports, express: port })
  } catch (error) {
    return stayUpAfterFailure(
      'server',
      `The server could not listen on port ${port}: ${error instanceof Error ? error.message : String(error)}.`,
      say,
      stopped
    )
  }
  // Nx mode only (this task never runs elsewhere): never in listen() / runManaged().
  const plan = planBrowserOpen({
    noOpenFlag: options.noOpen,
    env: process.env,
    auth: options.auth,
    host: options.host,
    state,
  })
  const log = (m: string) => console.log(`[server] ${m}`)
  // /welcome only needs Express, which is listening: open at once, whatever the profile.
  if ('url' in plan) openBrowser(plan.url, { log })
  else log(`Not opening the browser: ${plan.skip}`)
  await stopped
  return 0
}

const STATUS_POLL_MS = 1_000

/** A small pane: prints a line when something meaningful changes, nothing otherwise (see status.ts). */
export async function runStatusTask(): Promise<number> {
  const options = parseCodayOptions()
  const { signal, stopped } = foreground(async () => undefined)
  const report = createStatusReporter()
  const external = {
    ...(process.env.AGENTOS_URL ? { agentos: process.env.AGENTOS_URL } : {}),
    ...(process.env.FACTORY_URL ? { factory: process.env.FACTORY_URL } : {}),
  }
  const noManagedFactory = isManagedFactoryDisabled()
  const probe = async (port: number | undefined, path: string): Promise<boolean> =>
    port ? isHealthy(`http://${LOOPBACK}:${port}${path}`) : false

  while (!signal.aborted) {
    const state = readState(options.configDir)
    const agentosUrl = agentosHealthUrl(external.agentos, state)
    const [agentos, factory] = await Promise.all([
      agentosUrl ? isHealthy(agentosUrl) : false, // external: its own URL (what the proxy uses), else the managed port
      external.factory || noManagedFactory ? false : probe(state?.ports.factory, '/actuator/health'),
    ])
    const lines = report({
      state,
      setup: readWebSetup(options.configDir),
      healthy: { agentos, factory },
      external,
      factoryDisabled: noManagedFactory,
    })
    for (const line of lines) console.log(line)
    await Promise.race([new Promise((r) => setTimeout(r, STATUS_POLL_MS)), stopped])
  }
  return 0
}

// ---------------------------------------------------------------------------
// In-process mode (desktop apps, no TTY, CODAY_NO_NX=1)
// ---------------------------------------------------------------------------

export async function runManaged(): Promise<number> {
  const options = parseCodayOptions()
  teeConsoleToFile(logFilePath(options.configDir, 'server'))
  debugLog('INIT', 'Coday options:', options)

  const choice = await chooseStack(options)
  const expressPort = choice.ports.express
  const { decision } = await provision({
    ...profileInput(options),
    orchestrator: 'in-process',
    ports: choice.ports,
    portBlock: choice.base,
    log: (m) => debugLog('PROVISION', m),
  })
  const app = createWebApp({ codayOptions: options, setupEnabled: decision.setupEnabled })
  const server = await listen(app, options, expressPort)
  printStartup(choice.ports)

  // Every JVM this process spawned, healthy or still starting; adopted instances are never in it.
  const spawned: RunningService[] = []
  const shutdown = async (): Promise<void> => {
    await closeServer(server)
    // Factory first (it depends on AgentOS).
    for (const running of [...spawned].reverse()) await running.stop()
  }
  const { signal, stopped } = foreground(shutdown)

  // Fire-and-forget: the server is up, the wizard (or the proxy errors) shows the progress.
  void (async () => {
    const sinkFor = (name: string) =>
      createLogSink(options.configDir, name, (line) =>
        // Straight to stdout: the console tee would duplicate it into server.log.
        process.stdout.write(`${new Date().toISOString()} ${name.toUpperCase()} ${line}\n`)
      )
    const base = {
      configDir: options.configDir,
      baseEnv: process.env,
      signal,
      onSpawn: (running: RunningService) => void spawned.push(running),
      welcomeUrl: welcomeUrlOf,
    }
    const agentosCtx: OrchestratorContext = { ...base, sink: sinkFor('agentos') }
    const factoryCtx: OrchestratorContext = { ...base, sink: sinkFor('factory') }

    // An external Factory (FACTORY_URL) or CODAY_NO_FACTORY: nothing to download nor to start.
    const skipFactory = !!process.env.FACTORY_URL || isManagedFactoryDisabled()
    const factoryDownload = skipFactory ? undefined : provisionJars(factoryCtx, 'factory')
    let agentosUrl = process.env.AGENTOS_URL
    if (!agentosUrl) {
      const agentos = await startAgentosService(agentosCtx)
      if (!agentos) return
      agentosUrl = `http://127.0.0.1:${agentos.port}`
    }
    if (skipFactory) return
    await startFactoryService(factoryCtx, agentosUrl, factoryDownload)
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
    // No pane for a service Coday does not run (external URL, or the Factory left out): the launcher's Nx run then
    // never starts it, and `status` does not wait for it. The `status` pane still reports the external ones.
    withAgentos: !process.env.AGENTOS_URL,
    withFactory: !process.env.FACTORY_URL && !isManagedFactoryDisabled(),
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
    case 'status':
      return runStatusTask()
    default:
      return runManaged()
  }
}

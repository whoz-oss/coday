/**
 * The shared "how to bring AgentOS and the Factory up" logic. Used by:
 *   - the Nx `agentos` and `factory` tasks (each stays in the foreground, output in its pane),
 *   - the in-process `--managed` mode of the server (desktop apps, no TTY, no Nx).
 *
 * Both feed the SAME state.json, so the setup API reports identical progress either way.
 */

import { DownloadProgress, DownloadResult, ensureJarSet } from '../jar-set'
import { inspectJava, JavaStatus } from './java'
import { isHealthy, LogSink, RunningService, spawnService, waitForHealthy, waitUntil } from './process'
import { readIdentityEmail } from './web-setup'
import { ensureSecrets, RuntimeSecrets } from './secrets'
import { buildAgentosSpec, buildFactorySpec, factoryJarPath, agentosJarPath, LOOPBACK } from './services'
import { readState, RuntimeState, ServiceKind, updateState } from './state'
import * as fs from 'fs'
import { getAgentosVersion, getFactoryVersion } from '../version'

const AGENTOS_HEALTH_TIMEOUT_MS = 120_000
const FACTORY_HEALTH_TIMEOUT_MS = 90_000
const PROGRESS_WRITE_INTERVAL_MS = 400

export interface OrchestratorContext {
  configDir: string
  baseEnv: NodeJS.ProcessEnv
  /** Output of the orchestrator itself (waiting messages...). Also teed to the service log. */
  sink: LogSink
  /** Cancels every wait. Once aborted, no service is spawned any more. */
  signal?: AbortSignal
  /** Called right after a JVM is spawned, before its health wait, so that a stop can reach it while it starts. */
  onSpawn?: (running: RunningService) => void
  /** Public URL of the welcome page, for the waiting message. */
  welcomeUrl: (state: RuntimeState) => string
}

export const isSuccessfulDownload = (result: DownloadResult): boolean =>
  result.outcome === 'success' || result.outcome === 'skipped:all-present'

/** Download (or verify) one JAR set, publishing a throttled progress to state.json. */
export async function provisionJars(
  ctx: OrchestratorContext,
  kind: ServiceKind,
  version = kind === 'agentos' ? getAgentosVersion() : getFactoryVersion()
): Promise<DownloadResult> {
  const { configDir, sink } = ctx
  updateState(configDir, { downloads: { [kind]: { state: 'running', percent: 0 } } })
  let lastWrite = 0
  let lastLoggedDecile = -1
  const onProgress = (p: DownloadProgress): void => {
    const now = Date.now()
    if (now - lastWrite >= PROGRESS_WRITE_INTERVAL_MS || p.overallPercent === 100) {
      lastWrite = now
      updateState(configDir, { downloads: { [kind]: { state: 'running', percent: p.overallPercent } } })
    }
    const decile = Math.floor(p.overallPercent / 10)
    if (decile !== lastLoggedDecile) {
      lastLoggedDecile = decile
      sink.write(`[download] ${kind} ${p.overallPercent}%  (${p.artifactId}, ${p.index + 1}/${p.count})`)
    }
  }
  const result = await ensureJarSet(configDir, kind, version, onProgress)
  sink.write(`[download] ${kind}: ${result.outcome} - ${result.message}`)
  updateState(configDir, {
    downloads: {
      [kind]: isSuccessfulDownload(result)
        ? { state: 'done', percent: 100 }
        : {
            state: 'error',
            message:
              result.outcome === 'skipped:dev-version'
                ? `No ${kind} JAR was placed by hand for the 'dev' version`
                : result.message,
          },
    },
  })
  return result
}

/**
 * Wait until Java >= 25 is usable. In `unmanaged` profile a missing Java is a normal first-run state
 * the user fixes while the wizard explains how: poll. Otherwise give up immediately (null).
 */
async function requireJava(ctx: OrchestratorContext, patient: boolean): Promise<JavaStatus | null> {
  let announced = false
  const check = (): JavaStatus | null => {
    const java = inspectJava()
    updateState(ctx.configDir, { java })
    if (java.state === 'ok') return java
    if (!announced) {
      announced = true
      ctx.sink.write(`[java] ${java.hint}`)
      if (patient) ctx.sink.write('[java] Waiting for Java to be installed (checking every few seconds)...')
    }
    return null
  }
  const now = check()
  if (now || !patient) return now
  return (await waitUntil(check, { intervalMs: 3_000, signal: ctx.signal })) ?? null
}

export interface StartedService {
  port: number
  /** False when an already healthy instance was adopted instead of spawned. */
  spawned: boolean
  running?: RunningService
  shutdown(): Promise<void>
}

function requirePorts(state: RuntimeState | undefined): Required<
  Pick<RuntimeState['ports'], 'agentos' | 'agentosBolt'>
> & {
  express: number
  factory: number
} {
  const ports = state?.ports
  if (!state?.provisioned || !ports?.agentos || !ports.agentosBolt || !ports.express || !ports.factory) {
    throw new Error('Runtime state is not provisioned yet (run provision first)')
  }
  return { agentos: ports.agentos, agentosBolt: ports.agentosBolt, express: ports.express, factory: ports.factory }
}

function secretsFor(ctx: OrchestratorContext, state: RuntimeState): RuntimeSecrets | undefined {
  return state.profile === 'unmanaged'
    ? ensureSecrets(ctx.configDir, (m) => ctx.sink.write(`[secrets] ${m}`)).secrets
    : undefined
}

/**
 * Download, wait for the prerequisites (Java, then the identity in unmanaged profile), spawn AgentOS
 * and wait for its health. Returns null when AgentOS cannot be started (the reason was written
 * to the sink and to state.json).
 */
export async function startAgentosService(ctx: OrchestratorContext): Promise<StartedService | null> {
  const { configDir, sink } = ctx
  const state = readState(configDir)
  const ports = requirePorts(state)
  const version = state?.versions?.agentos ?? getAgentosVersion()
  const unmanaged = state?.profile === 'unmanaged'

  const download = await provisionJars(ctx, 'agentos', version)
  if (!isSuccessfulDownload(download)) {
    sink.write('[agentos] JARs are not available: AgentOS will not be started.')
    return null
  }
  if (!fs.existsSync(agentosJarPath(configDir, version))) {
    sink.write(`[agentos] JAR not found at ${agentosJarPath(configDir, version)}`)
    return null
  }

  const java = await requireJava(ctx, unmanaged)
  if (!java) return null

  // Unmanaged profile: AgentOS reads its bootstrap admin at first boot, so it must know the email.
  let identityEmail: string | undefined
  if (unmanaged) {
    identityEmail = readIdentityEmail(configDir)
    if (!identityEmail) {
      sink.write('')
      sink.write(`  >>> Waiting for first-run setup -> ${ctx.welcomeUrl(state as RuntimeState)}`)
      sink.write('')
      identityEmail = await waitUntil(() => readIdentityEmail(configDir), { intervalMs: 1_000, signal: ctx.signal })
      if (!identityEmail) return null
      sink.write(`[agentos] Identity received (${identityEmail}), starting AgentOS...`)
    }
  }

  // Adoption of an AgentOS already running on that port (orphan of a previous run, own bootRun...).
  const healthUrl = `http://${LOOPBACK}:${ports.agentos}/management/health`
  if (!unmanaged && (await isHealthy(healthUrl))) {
    sink.write(`[agentos] An AgentOS already answers on port ${ports.agentos}: adopting it (it will not be stopped).`)
    return { port: ports.agentos, spawned: false, shutdown: async () => undefined }
  }

  const spec = buildAgentosSpec({
    configDir,
    version,
    profile: state?.profile ?? 'managed',
    baseEnv: ctx.baseEnv,
    port: ports.agentos,
    boltPort: ports.agentosBolt,
    expressPort: ports.express,
    identityEmail,
    secrets: state ? secretsFor(ctx, state) : undefined,
  })
  fs.mkdirSync(spec.cwd, { recursive: true })
  if (ctx.signal?.aborted) return null
  const running = spawnService(spec, sink)
  ctx.onSpawn?.(running)
  updateState(configDir, { agentosStarted: true })

  const healthy = await waitForHealthy(spec.healthUrl, {
    timeoutMs: AGENTOS_HEALTH_TIMEOUT_MS,
    isDead: running.isDead,
  })
  if (!healthy) {
    sink.write('[agentos] AgentOS did not become healthy: stopping it.')
    await running.stop()
    return null
  }
  sink.write(`[agentos] Healthy on port ${ports.agentos}`)
  return { port: ports.agentos, spawned: true, running, shutdown: () => running.stop() }
}

/**
 * Same flow for the Factory; `agentosUrl` is the AgentOS the Factory must talk to. `download` lets the
 * caller start the (slow) JAR download earlier, while AgentOS is still coming up.
 */
export async function startFactoryService(
  ctx: OrchestratorContext,
  agentosUrl: string,
  download?: Promise<DownloadResult>
): Promise<StartedService | null> {
  const { configDir, sink } = ctx
  const state = readState(configDir)
  const ports = requirePorts(state)
  const version = state?.versions?.factory ?? getFactoryVersion()

  const downloaded = await (download ?? provisionJars(ctx, 'factory', version))
  if (!isSuccessfulDownload(downloaded)) {
    sink.write('[factory] JARs are not available: the Factory will not be started.')
    return null
  }
  if (!fs.existsSync(factoryJarPath(configDir, version))) {
    sink.write(`[factory] JAR not found at ${factoryJarPath(configDir, version)}`)
    return null
  }
  const java = await requireJava(ctx, state?.profile === 'unmanaged')
  if (!java) return null

  const spec = buildFactorySpec({
    configDir,
    version,
    profile: state?.profile ?? 'managed',
    baseEnv: ctx.baseEnv,
    port: ports.factory,
    agentosUrl,
    secrets: state ? secretsFor(ctx, state) : undefined,
  })
  fs.mkdirSync(`${spec.cwd}/plugins`, { recursive: true })
  if (ctx.signal?.aborted) return null
  const running = spawnService(spec, sink)
  ctx.onSpawn?.(running)
  const healthy = await waitForHealthy(spec.healthUrl, {
    timeoutMs: FACTORY_HEALTH_TIMEOUT_MS,
    isDead: running.isDead,
  })
  if (!healthy) {
    sink.write('[factory] The Factory did not become healthy: stopping it.')
    await running.stop()
    return null
  }
  sink.write(`[factory] Healthy on port ${ports.factory}`)
  return { port: ports.factory, spawned: true, running, shutdown: () => running.stop() }
}

/** Block until AgentOS answers (used by the factory task before it starts). */
export async function waitForAgentos(configDir: string, signal?: AbortSignal): Promise<string | undefined> {
  return waitUntil(
    async () => {
      const port = readState(configDir)?.ports.agentos
      if (!port) return undefined
      const url = `http://${LOOPBACK}:${port}`
      return (await isHealthy(`${url}/management/health`)) ? url : undefined
    },
    { intervalMs: 1_500, signal }
  )
}

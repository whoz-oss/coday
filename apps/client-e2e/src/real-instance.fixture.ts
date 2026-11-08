import { test as base, expect } from '@playwright/test'
import { spawn, execFileSync, type ChildProcess } from 'node:child_process'
import * as fs from 'node:fs'
import * as net from 'node:net'
import * as os from 'node:os'
import * as path from 'node:path'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { unsafeRootReason } from '../../../scripts/utils/web-local-helpers'

type Identity = { id: string; externalId: string; isAdmin: boolean }
type RealInstance = { baseURL: string; reader: string; caseId: string; namespaceId: string; content: string }
type State = { profile: string; ports: { express: number; agentos: number; agentosBolt: number } }
const workspace = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const pause = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

function ownedRoot(root: string, temp: string): void {
  const stat = fs.lstatSync(root)
  if (!stat.isDirectory() || stat.isSymbolicLink() || stat.uid !== process.getuid?.() ||
      fs.realpathSync(root) !== root || path.dirname(root) !== temp ||
      !path.basename(root).startsWith('coday-tc010-') || unsafeRootReason(root, os.homedir(), [workspace])) {
    throw new Error('Unsafe or unowned temporary root')
  }
}

async function candidatePort(): Promise<number> {
  return await new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address()
      if (!addr || typeof addr === 'string') return reject(new Error('No port assigned'))
      const port = addr.port
      server.close((err) => err ? reject(err) : resolve(port))
    })
  })
}

function processChildren(pid: number): number[] {
  const rows = execFileSync('/bin/ps', ['-axo', 'pid=,ppid='], { encoding: 'utf8' })
  return rows.split('\n').map((line) => line.trim().split(/\s+/).map(Number))
    .filter((pair) => pair[1] === pid).map((pair) => pair[0]!)
}

// lsof output contains the PID and numeric listener address. A missing lsof or ambiguous
// listener is an error, never evidence that a port belongs to this test.
function listeners(port: number): Array<{ pid: number; address: string }> {
  let output: string
  try {
    output = execFileSync('/usr/sbin/lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-Fpn'], { encoding: 'utf8' })
  } catch (error) {
    const e = error as { status?: number; stdout?: string }
    if (e.status === 1 && !e.stdout) return []
    throw error
  }
  let pid = 0
  const found: Array<{ pid: number; address: string }> = []
  for (const line of output.split('\n')) {
    if (line.startsWith('p')) pid = Number(line.slice(1))
    if (line.startsWith('n')) found.push({ pid, address: line.slice(1) })
  }
  return found
}

function requireListener(port: number, allowed: number[]): number | undefined {
  const found = listeners(port)
  if (!found.length) return undefined
  if (found.length !== 1 || !allowed.includes(found[0]!.pid) || found[0]!.address !== `127.0.0.1:${port}`) {
    throw new Error(`Unowned or non-loopback listener on port ${port}`)
  }
  return found[0]!.pid
}

function childExit(child: ChildProcess): Promise<void> {
  return new Promise((resolve, reject) => {
    child.once('error', reject)
    child.once('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`Bootstrap exited: code=${code}, signal=${signal}`)))
  })
}

function groupMembers(group: number): number[] {
  const rows = execFileSync('/bin/ps', ['-axo', 'pid=,pgid='], { encoding: 'utf8' })
  return rows.split('\n').map((line) => line.trim().split(/\s+/).map(Number))
    .filter((pair) => pair[1] === group).map((pair) => pair[0]!)
}

async function terminate(child: ChildProcess | undefined): Promise<void> {
  if (!child?.pid) return
  const group = child.pid // each spawn is detached and owns its new process group
  let members = groupMembers(group)
  if (members.length) {
    process.kill(-group, 'SIGTERM')
    const deadline = Date.now() + 30_000
    while ((members = groupMembers(group)).length && Date.now() < deadline) await pause(250)
    if (members.length) {
      process.kill(-group, 'SIGKILL')
      const killDeadline = Date.now() + 10_000
      while ((members = groupMembers(group)).length && Date.now() < killDeadline) await pause(250)
    }
  }
  if (members.length) throw new Error(`Owned process group ${group} still has ${members.join(',')}`)
  console.log(`TC-010 cleanup: owned process group ${group} stopped`)
}

async function waitForOwnedStack(child: ChildProcess, config: string, expressPort: number): Promise<{ state: State; agentPid: number }> {
  const deadline = Date.now() + 180_000
  while (Date.now() < deadline) {
    if (child.exitCode !== null || child.signalCode !== null || !child.pid) throw new Error('Server exited before readiness')
    let state: State | undefined
    try { state = JSON.parse(fs.readFileSync(path.join(config, 'runtime/state.json'), 'utf8')) as State } catch { /* provisioning */ }
    if (state?.profile === 'laptop' && state.ports?.express === expressPort && state.ports.agentos && state.ports.agentosBolt) {
      if ([state.ports.express, state.ports.agentos, state.ports.agentosBolt].includes(4100)) throw new Error('Forbidden port 4100')
      const children = processChildren(child.pid)
      const webPid = requireListener(expressPort, [child.pid])
      const agentPid = requireListener(state.ports.agentos, children)
      const boltPid = requireListener(state.ports.agentosBolt, children)
      if (webPid && agentPid && boltPid === agentPid) {
        const response = await fetch(`http://127.0.0.1:${state.ports.agentos}/management/health`, { signal: AbortSignal.timeout(3000) }).catch(() => null)
        if (response?.ok) {
          // Recheck after the health probe and before any synthetic identity is sent.
          if (requireListener(expressPort, [child.pid]) !== child.pid ||
              requireListener(state.ports.agentos, [agentPid]) !== agentPid ||
              requireListener(state.ports.agentosBolt, [agentPid]) !== agentPid) throw new Error('Listener changed during preflight')
          console.log(`TC-010 isolation: Express PID ${child.pid}:${expressPort}, AgentOS PID ${agentPid}:${state.ports.agentos}, Bolt ${state.ports.agentosBolt}; loopback`)
          return { state, agentPid }
        }
      }
    }
    await pause(500)
  }
  throw new Error('Timed out waiting for owned loopback listeners and healthy AgentOS')
}

export const test = base.extend<{ realInstance: RealInstance }>({
  realInstance: async ({}, use) => {
    const previousTmpdir = process.env.TMPDIR
    process.env.TMPDIR = '/private/tmp'
    try {
    const temp = fs.realpathSync(os.tmpdir())
    if (temp !== '/private/tmp' || fs.realpathSync('/private/tmp') !== '/private/tmp') {
      throw new Error('os.tmpdir() does not physically resolve to /private/tmp')
    }
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'coday-tc010-'))
    let prepare: ChildProcess | undefined
    let server: ChildProcess | undefined
    try {
      ownedRoot(root, temp)
      const ports = new Set<number>()
      while (ports.size < 3) {
        const port = await candidatePort()
        if (port !== 4100) ports.add(port)
      }
      const [expressPort, agentPort, boltPort] = [...ports] as [number, number, number]
      const admin = `tc010-admin-${randomUUID()}@example.test`
      const reader = `tc010-reader-${randomUUID()}@example.test`
      const env: NodeJS.ProcessEnv = {
        PATH: process.env.PATH, HOME: os.homedir(), JAVA_HOME: process.env.JAVA_HOME,
        TMPDIR: process.env.TMPDIR,
        CODAY_LOCAL_DIR: root, npm_config_cache: path.join(root, 'npm-cache'),
        CODAY_NO_OPEN: '1', CODAY_PROFILE: 'laptop', CODAY_HOST: '127.0.0.1',
        CODAY_NO_FACTORY: '1', CODAY_AGENTOS_VERSION: 'dev', CODAY_FACTORY_VERSION: 'dev',
        CODAY_AGENTOS_PORT: String(agentPort), CODAY_AGENTOS_BOLT_PORT: String(boltPort),
        AGENTOS_PERSISTENCE_EMBEDDED_BOLT_HOST: '127.0.0.1',
        AGENTOS_PERSONAL_NAMESPACE_ENABLED: 'false', PORT: String(expressPort),
      }
      // The only installation is web-local's local-tarball preparation in this owned root.
      prepare = spawn('pnpm', ['exec', 'tsx', 'scripts/web-local.ts', '--prepare-only'], {
        cwd: workspace, env, stdio: 'inherit', detached: true,
      })
      await childExit(prepare)
      ownedRoot(root, temp)
      const config = path.join(root, 'config')
      if (fs.realpathSync(config) !== config || !fs.statSync(config).isDirectory()) throw new Error('Unsafe config root')
      fs.writeFileSync(path.join(config, 'web-setup.json'), JSON.stringify({ identity: { email: admin }, setupDone: true }), { flag: 'wx', mode: 0o600 })
      const launcher = path.join(root, 'install/node_modules/@whoz-oss/coday-web/index.js')
      if (!fs.realpathSync(launcher).startsWith(root + path.sep)) throw new Error('Launcher outside owned root')
      server = spawn(process.execPath, [launcher, '--managed', '--auth', '--host', '127.0.0.1', '--configDir', config, '--no-open'], {
        cwd: path.join(root, 'install'), env, stdio: 'inherit', detached: true,
      })
      const { state, agentPid } = await waitForOwnedStack(server, config, expressPort)
      ownedRoot(root, temp)
      const data = path.join(config, 'agentos/data/database')
      if (!fs.realpathSync(data).startsWith(root + path.sep)) throw new Error('Database outside owned root')
      const baseURL = `http://127.0.0.1:${expressPort}`
      const api = async (identity: string, endpoint: string, init: RequestInit = {}): Promise<Response> => {
        if (!server?.pid || requireListener(expressPort, [server.pid]) !== server.pid ||
            requireListener(state.ports.agentos, [agentPid]) !== agentPid) throw new Error('Lost owned listener')
        return fetch(`${baseURL}/api/agentos${endpoint}`, {
          ...init, headers: { 'x-forwarded-email': identity, ...init.headers }, signal: AbortSignal.timeout(15_000),
        })
      }
      const json = async <T>(identity: string, endpoint: string, init?: RequestInit): Promise<T> => {
        const response = await api(identity, endpoint, init)
        if (!response.ok) throw new Error(`${endpoint}: HTTP ${response.status}`)
        return await response.json() as T
      }
      const request = (method: string, body: unknown): RequestInit => ({ method, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
      // First identity requests can create only synthetic users: personal provisioning is disabled.
      const adminUser = await json<Identity>(admin, '/api/users/me', { headers: { 'x-external-user-id': reader } })
      expect(adminUser.externalId).toBe(admin)
      expect(adminUser.isAdmin).toBe(true)
      const readerUser = await json<Identity>(reader, '/api/users/me', { headers: { 'x-external-user-id': admin } })
      expect(readerUser.externalId).toBe(reader)
      expect(readerUser.isAdmin).toBe(false)
      expect(readerUser.id).not.toBe(adminUser.id)
      console.log('TC-010 identities: distinct synthetic super-admin and non-admin reader; proxy overwrites spoofed IDs')
      const namespace = await json<{ id: string }>(admin, '/api/namespaces', request('POST', { name: `TC-010 ${randomUUID()}` }))
      const createdCase = await json<{ id: string; created: string }>(admin, '/api/cases', request('POST', { namespaceId: namespace.id, title: 'TC-010', worktree: false }))
      const memberResponse = await api(admin, `/api/namespaces/${namespace.id}/members/${readerUser.id}`, { method: 'PUT' })
      expect(memberResponse.status).toBe(200)
      const members = await json<Array<{ id: string; role: string }>>(admin, `/api/cases/${createdCase.id}/members`, request('PATCH', [{ userId: readerUser.id, role: 'MEMBER' }]))
      expect(members.find((m) => m.id === readerUser.id)?.role).toBe('MEMBER')
      const day = new Date(createdCase.created).toISOString().slice(0, 10).split('-')
      const exchange = path.join(config, 'agentos/data/exchange')
      const caseRoot = path.join(exchange, namespace.id, 'cases', ...day, createdCase.id)
      const namespaceRoot = path.join(exchange, namespace.id, 'shared')
      const content = 'Synthetic TC-010 guide content'
      for (const [dir, file, text] of [
        [path.join(caseRoot, 'docs/api'), 'guide.md', content],
        [path.join(caseRoot, 'current'), 'notes.txt', 'Synthetic current note'],
        [namespaceRoot, 'shared.txt', 'Synthetic shared note'],
      ]) {
        if (!dir!.startsWith(root + path.sep)) throw new Error('File path outside owned root')
        fs.mkdirSync(dir!, { recursive: true })
        if (!fs.realpathSync(dir!).startsWith(root + path.sep)) throw new Error('File path resolves outside owned root')
        fs.writeFileSync(path.join(dir!, file!), text!, { flag: 'wx' })
      }
      for (const scope of [`/api/cases/${createdCase.id}/files`, `/api/namespaces/${namespace.id}/files`]) {
        const manifest = await json<{ capability: string; files: Array<{ path: string }> }>(reader, `${scope}/manifest`)
        expect(manifest.capability).toBe('READ')
        expect(manifest.files.length).toBeGreaterThan(0)
        const denied = await api(reader, `${scope}?path=${encodeURIComponent(manifest.files[0]!.path)}`, { method: 'DELETE' })
        expect(denied.status).toBe(403)
      }
      console.log('TC-010 permissions: both manifests READ; DELETE denied 403 on both scopes')
      await use({ baseURL, reader, caseId: createdCase.id, namespaceId: namespace.id, content })
    } finally {
      let cleanupError: unknown
      try { await terminate(server) } catch (e) { cleanupError = e }
      try { await terminate(prepare) } catch (e) { cleanupError ??= e }
      try {
        if (cleanupError) throw new Error('Refusing to remove temporary data while process cleanup is unverified')
        ownedRoot(root, temp)
        fs.rmSync(root, { recursive: true, force: false })
        if (fs.existsSync(root)) throw new Error('Temporary root still exists after cleanup')
        console.log(`TC-010 cleanup: removed and verified absent ${root}`)
      } catch (e) { cleanupError ??= e }
      if (cleanupError) throw cleanupError
    }
    } finally {
      if (previousTmpdir === undefined) delete process.env.TMPDIR
      else process.env.TMPDIR = previousTmpdir
    }
  },
})
export { expect }

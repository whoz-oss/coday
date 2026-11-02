/**
 * Generated Nx workspace under `<configDir>/runtime/`: the process manager of the published package.
 *
 *   nx.json        no daemon, no cloud, no cache
 *   package.json   minimal (Nx only needs it to recognise a workspace root)
 *   project.json   one project `coday`:
 *                    provision  normal task, fast, local only (profile, secrets, Java, ports)
 *                    agentos    continuous, dependsOn provision, waits for the wizard's identity
 *                    factory    continuous, dependsOn provision, waits for AgentOS health
 *                    server     continuous, dependsOn provision, the Express server (opens the browser)
 *                    status     continuous, dependsOn provision + server, agentos, factory: the ENTRY task, a small
 *                               pane: download %, Java, health, URL
 *
 * Every command runs `node <server.js> <command> <forwarded args>`: the bundled server is the single
 * implementation, the workspace is only a manifest. The launcher runs ONE target, `nx run coday:status`, and Nx
 * starts the three services as its continuous dependencies, each in its own pane of the TUI.
 *
 * Why a single entry target and not `run-many -t status server agentos factory`: the Nx TUI pins (shows at startup)
 * the initiating task and its FIRST continuous dependency, but only for a single-target run (`run-many` pins
 * nothing). So `status` is pinned next to `server`, which is why `server` comes first in NX_SERVICES. Derived from
 * reading nx 23 (tasks-runner/run-command.js), the native TUI rendering must be confirmed visually.
 *
 * The services therefore must never exit while `status` runs: Nx reports an exited continuous dependency that
 * something unfinished depends on as `crashed`. See the `stayUp...` handling in commands.ts.
 * `--no-open` needs no special handling: buildForwardArgs forwards every option, the `server` task reads it.
 */

import * as fs from 'fs'
import * as path from 'path'
import { isCommand } from './command-names'
import { runtimeDir } from './paths'

export const NX_PROJECT = 'coday'
/** The entry task the launcher runs (`nx run coday:status`). */
export const NX_ENTRY_TASK = 'status'
/** The continuous services `status` depends on. ORDER MATTERS: the first one is pinned next to `status` in the TUI. */
export const NX_SERVICES = ['server', 'agentos', 'factory'] as const
export const NX_TASKS = [NX_ENTRY_TASK, ...NX_SERVICES] as const
type TaskName = 'provision' | (typeof NX_TASKS)[number]

export interface WorkspaceInput {
  configDir: string
  /** Absolute path of the node binary that runs the tasks. */
  nodePath: string
  /** Absolute path of the bundled server (server.js). */
  serverJs: string
  /** Arguments forwarded to every task (`--configDir ...`, `--host ...`, `--auth`). */
  forwardArgs: string[]
  platform?: NodeJS.Platform
}

export type WorkspaceFiles = Record<string, string>

/** Quote one shell argument for the shell Nx uses (sh on POSIX, cmd on Windows). */
export function shellQuote(arg: string, platform: NodeJS.Platform = process.platform): string {
  if (platform === 'win32') return `"${arg.replace(/"/g, '""')}"`
  return `"${arg.replace(/(["\\$`])/g, '\\$1')}"`
}

export function taskCommand(input: WorkspaceInput, command: TaskName): string {
  const platform = input.platform ?? process.platform
  return [input.nodePath, input.serverJs, command, ...input.forwardArgs].map((a) => shellQuote(a, platform)).join(' ')
}

export function generateWorkspace(input: WorkspaceInput): WorkspaceFiles {
  const target = (command: TaskName, continuous: boolean, dependsOn: string[] = ['provision']) => ({
    executor: 'nx:run-commands',
    cache: false,
    ...(continuous ? { continuous: true, dependsOn } : {}),
    options: { command: taskCommand(input, command) },
  })

  const nxJson = {
    $schema: 'https://nx.dev/reference/nx-json',
    useDaemonProcess: false,
    analytics: false,
    neverConnectToCloud: true,
    targetDefaults: { '*': { cache: false } },
    tui: { enabled: true, autoExit: false },
  }
  const packageJson = { name: 'coday-runtime', version: '0.0.0', private: true }
  const projectJson = {
    name: NX_PROJECT,
    $schema: 'node_modules/nx/schemas/project-schema.json',
    projectType: 'application',
    root: '.',
    targets: {
      provision: target('provision', false),
      server: target('server', true),
      agentos: target('agentos', true),
      factory: target('factory', true),
      // The entry task: waits for provision and starts the services (in this order, see NX_SERVICES).
      status: target('status', true, ['provision', ...NX_SERVICES]),
    },
  }
  return {
    'nx.json': JSON.stringify(nxJson, null, 2) + '\n',
    'package.json': JSON.stringify(packageJson, null, 2) + '\n',
    'project.json': JSON.stringify(projectJson, null, 2) + '\n',
  }
}

/** Write the workspace files that changed; returns the directory. */
export function writeWorkspace(input: WorkspaceInput): string {
  const dir = runtimeDir(input.configDir)
  fs.mkdirSync(dir, { recursive: true })
  for (const [name, content] of Object.entries(generateWorkspace(input))) {
    const file = path.join(dir, name)
    let current: string | undefined
    try {
      current = fs.readFileSync(file, 'utf8')
    } catch {
      current = undefined
    }
    if (current !== content) fs.writeFileSync(file, content)
  }
  return dir
}

/** Arguments to forward to the tasks: the raw ones minus the command word, with `--configDir` pinned. */
export function buildForwardArgs(rawArgs: string[], configDir: string): string[] {
  const args = [...rawArgs]
  // Only a known command word is dropped: the first non-dash token may well be the VALUE of an option
  // (`--configDir /x`).
  const commandIndex = args.findIndex((a) => isCommand(a))
  if (commandIndex >= 0 && args.slice(0, commandIndex).every((a) => a.startsWith('-'))) args.splice(commandIndex, 1)
  const hasConfigDir = args.some(
    (a) => a === '--configDir' || a.startsWith('--configDir=') || a.startsWith('--coday_config_dir')
  )
  return hasConfigDir ? args : [...args, '--configDir', configDir]
}

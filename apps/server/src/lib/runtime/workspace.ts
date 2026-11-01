/**
 * Generated Nx workspace under `<configDir>/runtime/`: the process manager of the published package.
 *
 *   nx.json        no daemon, no cloud, no cache
 *   package.json   minimal (Nx only needs it to recognise a workspace root)
 *   project.json   one project `coday`:
 *                    provision  normal task, fast, local only (profile, secrets, Java, ports)
 *                    agentos    continuous, dependsOn provision, waits for the wizard's identity
 *                    factory    continuous, dependsOn provision, waits for AgentOS health
 *                    server     continuous, dependsOn provision, the Express server
 *
 * Every command runs `node <server.js> <command> <forwarded args>`: the bundled server is the single
 * implementation, the workspace is only a manifest. The launcher runs
 * `nx run-many -t server agentos factory -p coday`: each continuous task gets its own pane in the TUI.
 */

import * as fs from 'fs'
import * as path from 'path'
import { isCommand } from './command-names'
import { runtimeDir } from './paths'

export const NX_PROJECT = 'coday'
export const NX_TASKS = ['server', 'agentos', 'factory'] as const

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

export function taskCommand(input: WorkspaceInput, command: 'provision' | 'agentos' | 'factory' | 'server'): string {
  const platform = input.platform ?? process.platform
  return [input.nodePath, input.serverJs, command, ...input.forwardArgs].map((a) => shellQuote(a, platform)).join(' ')
}

export function generateWorkspace(input: WorkspaceInput): WorkspaceFiles {
  const target = (command: 'provision' | 'agentos' | 'factory' | 'server', continuous: boolean) => ({
    executor: 'nx:run-commands',
    cache: false,
    ...(continuous ? { continuous: true, dependsOn: ['provision'] } : {}),
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

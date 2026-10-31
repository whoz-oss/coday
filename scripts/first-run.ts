/**
 * `nx run web:first-run` — reproduce EXACTLY what an `npx @whoz-oss/coday-web` user gets on a first run,
 * without publishing anything and without touching `~/.coday`.
 *
 *   1. `pnpm pack` the four npm packages (client, factory-cockpit, server, web) from their build output
 *      (the Nx target builds them first, along with the AgentOS and Factory boot JARs),
 *   2. `npm install` the tarballs into a FRESH temp directory (what npx does, minus the registry),
 *   3. drop the locally built JARs into a FRESH, throwaway --configDir using the `dev` version sentinel layout
 *      (`<configDir>/agentos/agentos-service-dev.jar`, plugins in `plugins/`, same for factory),
 *   4. run the INSTALLED `coday-web --configDir <tmp>` with CODAY_AGENTOS_VERSION=dev / CODAY_FACTORY_VERSION=dev
 *      so nothing is downloaded.
 *
 * Options (after `--`):
 *   --managed        run everything in one process, without the Nx TUI (what the desktop apps get; used by e2e)
 *   --port <n>       Express port (default 3000, next free one otherwise)
 *   --prepare-only   prepare everything, print the paths as JSON on the last line and exit (no server)
 *   --clean          delete the temp directory when the server stops (default: kept, path printed)
 *
 * Env: CODAY_FIRST_RUN_DIR=<dir> reuses a directory instead of creating a temp one.
 */

import { execFileSync, spawn } from 'child_process'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { fileURLToPath } from 'url'
import {
  AGENTOS_PLUGINS,
  builtJarGlob,
  devJarTarget,
  FACTORY_PLUGINS,
  installManifest,
  NPM_PACKAGES,
  tarballPrefix,
} from './utils/first-run-helpers'

const workspaceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const log = (message: string) => console.log(`[first-run] ${message}`)

function findBuiltJar(kind: 'agentos' | 'factory', artifactId: string): string {
  const { dir, startsWith } = builtJarGlob(kind, artifactId)
  const absolute = path.join(workspaceRoot, dir)
  const candidates = fs.existsSync(absolute)
    ? fs
        .readdirSync(absolute)
        .filter(
          (f) =>
            f.startsWith(startsWith) && f.endsWith('.jar') && !f.endsWith('-plain.jar') && !f.endsWith('-sources.jar')
        )
    : []
  // Most recently built first: build/libs may contain JARs of previous versions.
  candidates.sort((a, b) => fs.statSync(path.join(absolute, b)).mtimeMs - fs.statSync(path.join(absolute, a)).mtimeMs)
  const found = candidates[0]
  if (!found)
    throw new Error(`No built JAR for ${artifactId} in ${absolute} (run: pnpm nx run web:build-first-run-jars)`)
  return path.join(absolute, found)
}

function placeJars(configDir: string): void {
  for (const [kind, service, plugins] of [
    ['agentos', 'agentos-service', AGENTOS_PLUGINS],
    ['factory', 'factory-service', FACTORY_PLUGINS],
  ] as const) {
    for (const artifactId of [service, ...plugins]) {
      const target = devJarTarget(configDir, kind, artifactId)
      fs.mkdirSync(path.dirname(target), { recursive: true })
      fs.copyFileSync(findBuiltJar(kind, artifactId), target)
    }
    log(`${kind}: ${1 + plugins.length} JAR(s) placed in ${path.join(configDir, kind)}`)
  }
}

function pack(destination: string): Record<string, string> {
  const tarballs: Record<string, string> = {}
  for (const pkg of NPM_PACKAGES) {
    const cwd = path.join(workspaceRoot, pkg.dir)
    if (!fs.existsSync(path.join(cwd, 'package.json'))) {
      throw new Error(`${pkg.name}: ${cwd}/package.json not found (run the Nx builds first)`)
    }
    log(`pnpm pack ${pkg.name}`)
    execFileSync('pnpm', ['pack', '--pack-destination', destination], { cwd, stdio: ['ignore', 'ignore', 'inherit'] })
    const file = fs
      .readdirSync(destination)
      .filter((f) => f.startsWith(tarballPrefix(pkg.name)) && f.endsWith('.tgz'))
      .sort()
      .pop()
    if (!file) throw new Error(`pnpm pack produced no tarball for ${pkg.name}`)
    tarballs[pkg.name] = path.join(destination, file)
  }
  return tarballs
}

function javaNote(): string | undefined {
  try {
    const bin = process.env['JAVA_HOME'] ? path.join(process.env['JAVA_HOME'], 'bin', 'java') : 'java'
    const out = execFileSync(bin, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    const major = parseInt(out.match(/^\S+\s+(\d+)/)?.[1] ?? '0', 10)
    return major >= 25 ? undefined : `Java ${major} found, Java 25+ is required: the wizard will ask you to install it.`
  } catch {
    return 'No Java found: the wizard will show the install hint (this is part of the first-run experience).'
  }
}

async function main(): Promise<void> {
  const args = process.argv.slice(2)
  const flag = (name: string) => args.includes(name)
  const value = (name: string) => {
    const i = args.indexOf(name)
    return i >= 0 ? args[i + 1] : undefined
  }

  const root = process.env['CODAY_FIRST_RUN_DIR'] ?? fs.mkdtempSync(path.join(os.tmpdir(), 'coday-first-run-'))
  const packs = path.join(root, 'packs')
  const install = path.join(root, 'install')
  const configDir = path.join(root, 'config')
  for (const dir of [packs, install, configDir]) fs.mkdirSync(dir, { recursive: true })

  log(`temp directory : ${root}`)
  log(`install dir    : ${install}   (the installed packages, like npx)`)
  log(`configDir      : ${configDir}   (throwaway; your ~/.coday is NOT touched)`)
  log(`logs           : ${path.join(configDir, 'logs')}/{server,agentos,factory}.log`)

  const tarballs = pack(packs)
  fs.writeFileSync(path.join(install, 'package.json'), JSON.stringify(installManifest(tarballs), null, 2))
  log('npm install of the tarballs (fresh directory)...')
  execFileSync('npm', ['install', '--no-audit', '--no-fund', '--loglevel=error'], { cwd: install, stdio: 'inherit' })

  placeJars(configDir)

  const bin = path.join(install, 'node_modules', '.bin', process.platform === 'win32' ? 'coday-web.cmd' : 'coday-web')
  const launchArgs = ['--configDir', configDir, ...(flag('--managed') ? ['--managed'] : [])]
  const port = value('--port') ?? '3000'
  const env = {
    ...process.env,
    CODAY_AGENTOS_VERSION: 'dev',
    CODAY_FACTORY_VERSION: 'dev',
    ...(value('--port') ? { PORT: port } : {}),
  }

  if (flag('--prepare-only')) {
    console.log(
      JSON.stringify({
        root,
        install,
        configDir,
        bin,
        args: launchArgs,
        port,
        env: { CODAY_AGENTOS_VERSION: 'dev', CODAY_FACTORY_VERSION: 'dev' },
      })
    )
    return
  }
  const note = javaNote()
  if (note) log(`NOTE: ${note}`)
  log(`running: ${bin} ${launchArgs.join(' ')}`)
  log(`Open http://localhost:${port} (or the URL printed below) and walk through the wizard.`)

  const child = spawn(bin, launchArgs, { cwd: install, env, stdio: 'inherit', shell: process.platform === 'win32' })
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP'] as const) process.on(signal, () => child.kill(signal))
  const code = await new Promise<number>((resolve) => child.on('exit', (c) => resolve(c ?? 0)))

  if (flag('--clean')) fs.rmSync(root, { recursive: true, force: true })
  else log(`done. Inspect or delete: ${root}`)
  process.exit(code)
}

main().catch((error) => {
  console.error('[first-run] failed:', error instanceof Error ? error.message : error)
  process.exit(1)
})

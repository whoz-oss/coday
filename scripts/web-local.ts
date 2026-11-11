/**
 * `pnpm nx run web:local` — build the local code and run it EXACTLY like an `npx @whoz-oss/coday-web` user would,
 * without publishing anything and without ever touching `~/.coday` or `~/Coday`.
 *
 * End users always run the same command, `npx @whoz-oss/coday-web`, and the browser always opens on `/welcome`: the
 * first-run wizard while the setup is not done, otherwise a loading page that leads into the app. This command behaves
 * the same way: ONE command whatever the setup state. The first time you get the wizard, afterwards you resume your
 * configured instance.
 *
 * Everything lives in a PERSISTENT root (default `~/.coday-local`):
 *
 *   <root>/packs/        the `pnpm pack` tarballs (emptied on every run)
 *   <root>/install/      the tarballs installed with npm, like npx does (recreated on every run)
 *   <root>/config/       the --configDir: web-setup.json, AgentOS and Factory data, secrets (KEPT between runs)
 *   <root>/files/Coday   the folder the wizard proposes instead of ~/Coday
 *
 * Every run:
 *   1. `pnpm pack` the four npm packages (client, factory-cockpit, server, web) from their build output
 *      (the Nx target builds them first, along with the AgentOS and Factory boot JARs),
 *   2. `npm install` the tarballs in a clean `install/` (the code may have changed),
 *   3. copy the locally built JARs into `config/{agentos,factory}` using the `dev` version sentinel layout
 *      (`agentos-service-dev.jar`, plugins in `plugins/`), overwriting the previous ones; the data directories next
 *      to them are never touched,
 *   4. run the INSTALLED `coday-web --configDir <root>/config` with CODAY_AGENTOS_VERSION=dev /
 *      CODAY_FACTORY_VERSION=dev so nothing is downloaded.
 *
 * Options (after `--`):
 *   --fresh          delete `config/` and `files/` first: a brand-new first run (the wizard shows again)
 *   --managed        run everything in one process, without the Nx TUI (what the desktop apps get; used by e2e)
 *   --no-open        do not open the browser when the server is up (Nx mode opens it; also CODAY_NO_OPEN=1)
 *   --port <n>       Express port, STRICT (an explicit choice: taken, the run fails). Without it the stack always takes the
 *                    first block of the LOCAL profile (3010; the prod block 3000 never collides) and FAILS when one of
 *                    its ports is taken, naming the process holding it; see
 *                    apps/server/src/lib/runtime/port-scheme.json and apps/web/README.md
 *   --prepare-only   prepare everything, print the paths as JSON on the last line and exit (no server)
 *   --clean          delete the WHOLE root (config included) when the server stops
 *
 * Env:
 *   CODAY_LOCAL_DIR=<dir>      use another root than ~/.coday-local
 *   CODAY_FIRST_RUN_DIR=<dir>  deprecated alias of CODAY_LOCAL_DIR
 *   CODAY_PORT_BASE=<n>        use this port block instead of the default one of the profile (taken: the run fails)
 */

import { execFileSync, spawn } from 'child_process'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { fileURLToPath } from 'url'
// Leaf module (imports only port-scheme.json: the single definition of the port scheme): a relative import is the only
// way for this tooling project to share it, hence the exception to the module boundaries rule.
// eslint-disable-next-line @nx/enforce-module-boundaries
import { parsePortBase, PORT_BASES, portsFor } from '../apps/server/src/lib/runtime/port-scheme'
import {
  builtJarGlob,
  devJarTarget,
  installManifest,
  isSetupDone,
  localLayout,
  NPM_PACKAGES,
  pluginArtifactIds,
  resolveLocalRoot,
  tarballPrefix,
  unsafeRootReason,
} from './utils/web-local-helpers'

const workspaceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * Port scheme of the local stack (apps/server/src/lib/runtime/port-scheme.json): the LOCAL profile owns the block 3010
 * (prod has 3000: never the same block). The ports are ALWAYS the same (OAuth redirect URI allowlists are tied to
 * them): when one is taken the server refuses to start and names the process holding it. `--port` or a
 * `CODAY_PORT_BASE` of the caller are the explicit ways to choose other ports.
 */
const EXPLICIT_BASE = parsePortBase(process.env['CODAY_PORT_BASE'])
const EXPECTED_PORT = portsFor(EXPLICIT_BASE ?? PORT_BASES.local).server

const log = (message: string) => console.log(`[web-local] ${message}`)

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

/** Plugins present in the repository right now: the `<kind>-*-plugin` directories of `<workspace>/<kind>/`. */
function discoverPlugins(kind: 'agentos' | 'factory'): string[] {
  const dir = path.join(workspaceRoot, kind)
  const entries = fs.readdirSync(dir, { withFileTypes: true }).filter((e) => e.isDirectory())
  return pluginArtifactIds(
    kind,
    entries.map((e) => e.name)
  )
}

/**
 * Overwrite the JARs in `<configDir>/{agentos,factory}`. Only the `*-dev.jar` files are written (copyFileSync
 * replaces them): nothing is deleted, so `agentos/data`, `factory/data` and the rest of a kept config survive.
 * The plugins are the ones the repository holds (see `discoverPlugins`), not a fixed list.
 */
function placeJars(configDir: string): void {
  for (const [kind, service] of [
    ['agentos', 'agentos-service'],
    ['factory', 'factory-service'],
  ] as const) {
    const plugins = discoverPlugins(kind)
    log(`${kind}: plugins found in the repository: ${plugins.join(', ') || '(none)'}`)
    for (const artifactId of [service, ...plugins]) {
      const target = devJarTarget(configDir, kind, artifactId)
      fs.mkdirSync(path.dirname(target), { recursive: true })
      fs.copyFileSync(findBuiltJar(kind, artifactId), target)
    }
    log(`${kind}: ${1 + plugins.length} JAR(s) placed (overwritten) in ${path.join(configDir, kind)}`)
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

/** Empty a directory and recreate it (these directories only ever hold generated artefacts). */
function resetDir(dir: string): void {
  fs.rmSync(dir, { recursive: true, force: true })
  fs.mkdirSync(dir, { recursive: true })
}

function readSetupDone(configDir: string): boolean {
  try {
    return isSetupDone(JSON.parse(fs.readFileSync(path.join(configDir, 'web-setup.json'), 'utf8')))
  } catch {
    return false
  }
}

async function main(): Promise<void> {
  const args = process.argv.slice(2)
  const flag = (name: string) => args.includes(name)
  const value = (name: string) => {
    const i = args.indexOf(name)
    return i >= 0 ? args[i + 1] : undefined
  }

  const { root, source } = resolveLocalRoot(process.env, os.homedir())
  const unsafe = unsafeRootReason(root, os.homedir(), [workspaceRoot])
  if (unsafe) throw new Error(`Refusing to use ${root} as the local root: ${unsafe}.`)
  const { packs, install, config: configDir, files, filesFolder } = localLayout(root)

  log(`root           : ${root}   (${source === 'default' ? 'default, persistent' : source})`)
  if (source !== 'default' && source !== 'CODAY_LOCAL_DIR') log(`WARNING: ${source}`)

  if (flag('--fresh')) {
    log('--fresh: deleting')
    for (const dir of [configDir, files]) {
      log(`  - ${dir}${fs.existsSync(dir) ? '' : '   (did not exist)'}`)
      fs.rmSync(dir, { recursive: true, force: true })
    }
    log('  (the wizard will show: this is a brand-new first run)')
  }
  for (const dir of [install, configDir]) fs.mkdirSync(dir, { recursive: true })

  const firstRun = !readSetupDone(configDir)
  log(`install dir    : ${install}   (the installed packages, like npx; recreated on every run)`)
  log(`configDir      : ${configDir}   (kept between runs; your ~/.coday is NOT touched)`)
  log(`files folder   : ${filesFolder}   (proposed by the wizard instead of ~/Coday)`)
  log(`logs           : ${path.join(configDir, 'logs')}/{server,agentos,factory}.log`)
  log(
    firstRun
      ? 'state          : FIRST RUN (no setupDone in web-setup.json): the wizard will show at /welcome'
      : 'state          : RESUME (setup already done): /welcome is a loading page that leads into the app, your configuration is kept'
  )

  // Packs and install hold generated artefacts only: start from scratch so that stale tarballs are never picked
  // and npm cannot trip on the integrity of a tarball rebuilt under the same file name.
  resetDir(packs)
  resetDir(install)
  const tarballs = pack(packs)
  fs.writeFileSync(path.join(install, 'package.json'), JSON.stringify(installManifest(tarballs), null, 2))
  log('npm install of the tarballs (clean directory)...')
  execFileSync('npm', ['install', '--no-audit', '--no-fund', '--loglevel=error'], { cwd: install, stdio: 'inherit' })

  placeJars(configDir)

  const bin = path.join(install, 'node_modules', '.bin', process.platform === 'win32' ? 'coday-web.cmd' : 'coday-web')
  const launchArgs = [
    '--configDir',
    configDir,
    ...(flag('--managed') ? ['--managed'] : []),
    ...(flag('--no-open') ? ['--no-open'] : []),
  ]
  const port = value('--port')
  const env = {
    ...process.env,
    CODAY_AGENTOS_VERSION: 'dev',
    CODAY_FACTORY_VERSION: 'dev',
    // The wizard proposes this folder instead of ~/Coday: the local stack never writes in the real home.
    CODAY_DEFAULT_FILES_DIR: filesFolder,
    // The LOCAL profile: default block 3010. A taken port makes the start fail; CODAY_PORT_BASE or --port of the caller,
    // below, choose other ports explicitly.
    CODAY_PORT_PROFILE: 'local',
    ...(port ? { PORT: port } : {}),
  }

  if (flag('--prepare-only')) {
    console.log(
      JSON.stringify({
        root,
        install,
        configDir,
        bin,
        args: launchArgs,
        port: port ?? null,
        firstRun,
        env: {
          CODAY_AGENTOS_VERSION: 'dev',
          CODAY_FACTORY_VERSION: 'dev',
          CODAY_PORT_PROFILE: 'local',
          ...(EXPLICIT_BASE ? { CODAY_PORT_BASE: String(EXPLICIT_BASE) } : {}),
        },
      })
    )
    return
  }
  const note = javaNote()
  if (note) log(`NOTE: ${note}`)
  log(`running: ${bin} ${launchArgs.join(' ')}`)
  const where = port ? `http://localhost:${port}/` : `http://localhost:${EXPECTED_PORT}/`
  log(
    firstRun ? `Open ${where} and walk through the wizard.` : `Open ${where}: you are back in your configured instance.`
  )

  const child = spawn(bin, launchArgs, { cwd: install, env, stdio: 'inherit', shell: process.platform === 'win32' })
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP'] as const) process.on(signal, () => child.kill(signal))
  const code = await new Promise<number>((resolve) => child.on('exit', (c) => resolve(c ?? 0)))

  if (flag('--clean')) {
    log(`--clean: deleting the whole root ${root} (setup and data included)`)
    fs.rmSync(root, { recursive: true, force: true })
  } else {
    log(`done. Restart with the same command to resume; add --fresh to start over. Root: ${root}`)
  }
  process.exit(code)
}

main().catch((error) => {
  console.error('[web-local] failed:', error instanceof Error ? error.message : error)
  process.exit(1)
})

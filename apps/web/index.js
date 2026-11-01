#!/usr/bin/env node
import { fileURLToPath, pathToFileURL } from 'url';
import { dirname, resolve } from 'path';
import { existsSync } from 'fs';
import { spawn, spawnSync } from 'child_process';
import { createRequire } from 'module';
import { chooseMode, nxArgs, nxEnv, parseWorkspaceDir, resolveConfigDir, serverArgs } from './launcher.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

/**
 * Coday Web Launcher
 *
 * 1. Resolves the client, factory cockpit and server packages at runtime.
 * 2. Interactive terminal: writes a generated Nx workspace under <configDir>/runtime/ and runs the
 *    bundled `nx` on it, so the server, AgentOS and the Factory each get their own pane and log file.
 * 3. No TTY (desktop apps), --managed or CODAY_NO_NX=1: runs everything inside the server process,
 *    exactly like before (it still prints `Server is running on http://localhost:PORT`).
 */

// Create require function for resolving packages
const require = createRequire(import.meta.url);

/**
 * Resolve a static package location. Tries, in order:
 * 1. Standard node_modules resolution (production/npx)
 * 2. Relative path fallback (monorepo development)
 */
function resolveStaticPackagePath(packageName, developmentPath, displayName) {
  const developmentBrowserPath = resolve(__dirname, developmentPath);

  try {
    const packageJsonPath = require.resolve(`${packageName}/package.json`);
    const packagePath = dirname(packageJsonPath);
    const browserPath = resolve(packagePath, 'browser');

    if (existsSync(browserPath)) {
      return browserPath;
    }

    // Keep compatibility with packages exposing their browser build at the root.
    if (existsSync(resolve(packagePath, 'index.html'))) {
      return packagePath;
    }

    if (existsSync(developmentBrowserPath)) {
      console.log(`Using development ${displayName.toLowerCase()} build from monorepo`);
      return developmentBrowserPath;
    }

    console.error(`${displayName} package found but browser directory is missing.`);
    console.error(`Checked paths: ${browserPath}, ${packagePath}, ${developmentBrowserPath}`);
    process.exit(1);
  } catch (error) {
    if (existsSync(developmentBrowserPath)) {
      console.log(`Using development ${displayName.toLowerCase()} build from monorepo`);
      return developmentBrowserPath;
    }

    console.error(`Could not resolve ${packageName} package.`);
    console.error('Please ensure dependencies are installed and the application is built.');
    console.error('Error:', error.message);
    process.exit(1);
  }
}

function resolveServerMain() {
  try {
    const serverPackagePath = require.resolve('@whoz-oss/coday-server/package.json');
    const packagedServerMainPath = resolve(dirname(serverPackagePath), 'server.js');
    const developmentServerMainPath = resolve(__dirname, '../server/dist/server.js');
    const serverMainPath = existsSync(packagedServerMainPath) ? packagedServerMainPath : developmentServerMainPath;
    if (!existsSync(serverMainPath)) {
      throw new Error(
        `Server entry point is missing. Checked paths: ${packagedServerMainPath}, ${developmentServerMainPath}`
      );
    }
    return serverMainPath;
  } catch (error) {
    console.error('Could not resolve @whoz-oss/coday-server package.');
    console.error('Please ensure dependencies are installed: pnpm install');
    console.error('Error:', error.message);
    process.exit(1);
  }
}

// Resolve and expose static application paths to the server (and, through env inheritance, to every Nx task).
const clientPath = resolveStaticPackagePath('@whoz-oss/coday-client', '../client/dist/browser', 'Client');
const factoryCockpitPath = resolveStaticPackagePath(
  '@whoz-oss/coday-factory-cockpit',
  '../factory-cockpit/dist/browser',
  'Factory cockpit'
);
process.env.CODAY_CLIENT_PATH = clientPath;
process.env.CODAY_FACTORY_COCKPIT_PATH = factoryCockpitPath;

console.log(`Coday Web: Using client files from ${clientPath}`);
console.log(`Coday Web: Using factory cockpit files from ${factoryCockpitPath}`);

const serverMainPath = resolveServerMain();
const rawArgs = process.argv.slice(2);
const { mode, reason } = chooseMode({ argv: rawArgs, env: process.env, isTTY: process.stdout.isTTY });

async function runManaged() {
  // The server reads process.argv: hide the launcher-only flags from it.
  process.argv = [process.argv[0], process.argv[1], ...serverArgs(rawArgs)];
  // Convert Windows absolute path to file:// URL for ESM import
  await import(pathToFileURL(serverMainPath).href);
}

function runNx() {
  const configDir = resolveConfigDir(rawArgs);

  let nxBin;
  try {
    nxBin = require.resolve('nx/bin/nx.js');
  } catch (error) {
    console.warn(`Nx is not available (${error.message}): falling back to the in-process mode.`);
    return runManaged();
  }

  // 1. Generate the Nx workspace (the bundled server knows how: single implementation).
  const generated = spawnSync(process.execPath, [serverMainPath, 'workspace', ...serverArgs(rawArgs)], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'inherit'],
    env: process.env,
  });
  const workspaceDir = parseWorkspaceDir(generated.stdout);
  if (generated.status !== 0 || !workspaceDir || !existsSync(workspaceDir)) {
    console.error('Could not generate the runtime workspace; falling back to the in-process mode.');
    return runManaged();
  }

  console.log(`Coday Web: runtime workspace ${workspaceDir} (logs in ${resolve(configDir, 'logs')})`);

  // 2. Run the continuous tasks. Nx draws one pane per process, in the terminal.
  const nx = spawn(process.execPath, [nxBin, ...nxArgs()], {
    cwd: workspaceDir,
    env: nxEnv(process.env, configDir, rawArgs),
    stdio: 'inherit',
  });
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    process.on(signal, () => nx.kill(signal));
  }
  nx.on('error', (error) => {
    console.error('Failed to start nx:', error.message);
    process.exit(1);
  });
  nx.on('exit', (code) => process.exit(code ?? 0));
  return undefined;
}

console.log(`Coday Web: ${mode} mode (${reason})`);
(mode === 'nx' ? Promise.resolve().then(runNx) : runManaged()).catch((error) => {
  console.error('Failed to start server:', error);
  process.exit(1);
});

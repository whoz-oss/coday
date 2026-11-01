/**
 * Pure helpers of the coday-web launcher (no side effects at import time).
 * Tested with `node --test` (see launcher.test.js, run by the `web:test` Nx target).
 */
import { homedir } from 'os';
import { join } from 'path';

/**
 * Decide how to run: `nx` (generated workspace, one pane per process) or `managed` (everything in the
 * server process, which is what the desktop apps need).
 *
 * managed when: `--managed` flag, `CODAY_NO_NX=1`, or stdout is not a TTY (desktop apps spawn us
 * without one, and the Nx TUI needs a terminal anyway).
 */
export function chooseMode({ argv, env, isTTY }) {
  if (argv.includes('--managed')) return { mode: 'managed', reason: '--managed flag' };
  if (env.CODAY_NO_NX === '1' || env.CODAY_NO_NX === 'true') return { mode: 'managed', reason: 'CODAY_NO_NX is set' };
  if (!isTTY) return { mode: 'managed', reason: 'no TTY (desktop app, CI, pipe)' };
  return { mode: 'nx', reason: 'interactive terminal' };
}

/** `--configDir <dir>` / `--configDir=<dir>` / `--coday_config_dir ...`, default `~/.coday`. */
export function resolveConfigDir(argv, home = homedir()) {
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    for (const flag of ['--configDir', '--coday_config_dir']) {
      if (arg === flag && argv[i + 1]) return argv[i + 1];
      if (arg.startsWith(`${flag}=`)) return arg.slice(flag.length + 1);
    }
  }
  return join(home, '.coday');
}

/** Flags only the launcher understands. */
const LAUNCHER_ONLY_FLAGS = ['--managed', '--no-open'];

/** The arguments the server must see: the launcher's own (`--managed`, `--no-open`) are removed. */
export function serverArgs(argv) {
  return argv.filter((a) => !LAUNCHER_ONLY_FLAGS.includes(a));
}

/** The browser opens on start unless `--no-open` or `CODAY_NO_OPEN=1|true` says otherwise. */
export function shouldOpenBrowser(argv, env) {
  if (argv.includes('--no-open')) return false;
  return env.CODAY_NO_OPEN !== '1' && env.CODAY_NO_OPEN !== 'true';
}

/** Marker the server's `workspace` command prints before the directory it generated. */
export const WORKSPACE_MARKER = 'CODAY_RUNTIME_WORKSPACE=';

/** The generated workspace directory, found among any other stdout lines; undefined when absent. */
export function parseWorkspaceDir(stdout) {
  const line = String(stdout ?? '')
    .split('\n')
    .map((l) => l.trim())
    .reverse()
    .find((l) => l.startsWith(WORKSPACE_MARKER));
  const dir = line?.slice(WORKSPACE_MARKER.length).trim();
  return dir || undefined;
}

/** Arguments of `nx run-many` for the four continuous tasks (each gets its own TUI pane). */
export function nxArgs() {
  return ['run-many', '-t', 'server', 'agentos', 'factory', 'status', '-p', 'coday', '--parallel=5', '--outputStyle=tui'];
}

/**
 * Environment of the Nx process: no daemon, no cloud, no cache. Only used in the interactive Nx mode, which is
 * why the browser opt-in (`CODAY_OPEN_BROWSER=1`, read by the server task) is set here and nowhere else.
 */
export function nxEnv(env, configDir, argv = []) {
  const base = { ...env };
  // The opt-in is decided here only: a value inherited from the caller's environment does not count.
  delete base.CODAY_OPEN_BROWSER;
  return {
    ...base,
    ...(shouldOpenBrowser(argv, env) ? { CODAY_OPEN_BROWSER: '1' } : {}),
    NX_DAEMON: 'false',
    NX_NO_CLOUD: 'true',
    NX_SKIP_NX_CACHE: 'true',
    NX_TUI: 'true',
    NX_WORKSPACE_DATA_DIRECTORY: join(configDir, 'runtime', '.nx'),
    NX_CACHE_DIRECTORY: join(configDir, 'runtime', '.nx', 'cache'),
  };
}

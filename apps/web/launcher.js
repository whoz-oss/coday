/**
 * Pure helpers of the coday-web launcher (no side effects at import time).
 * Tested with `node --test` (see launcher.test.js, run by the `web:test` Nx target).
 */
import { homedir } from 'os';
import { join, resolve } from 'path';

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

/**
 * `--configDir <dir>` / `--configDir=<dir>` / `--coday_config_dir ...`, default `~/.coday`. Always absolute: Nx and
 * its tasks run from `<configDir>/runtime`, where a relative directory would point somewhere else.
 */
export function resolveConfigDir(argv, home = homedir()) {
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    for (const flag of ['--configDir', '--coday_config_dir']) {
      if (arg === flag && argv[i + 1]) return resolve(argv[i + 1]);
      if (arg.startsWith(`${flag}=`)) return resolve(arg.slice(flag.length + 1));
    }
  }
  return join(home, '.coday');
}

/** Flags the launcher handles itself; the server never sees them. Both `--flag` and `--flag=value` forms. */
const LAUNCHER_FLAGS = ['--managed', '--skip-doctor'];

const hasFlag = (argv, flag) => argv.some((a) => a === flag || a.startsWith(`${flag}=`));

/** The arguments the server must see: the launcher's own are removed. */
export function serverArgs(argv) {
  return argv.filter((a) => !LAUNCHER_FLAGS.some((flag) => a === flag || a.startsWith(`${flag}=`)));
}

/**
 * `coday-web doctor`: run the preflight and nothing else. Same rule as the bundled server's `parseCommand` (the
 * first positional wins), so the command means the same thing with or without a terminal — without this the Nx
 * branch would print the report and then start the whole stack, which is not what anyone typing `doctor` wants.
 */
export function wantsDoctorOnly(argv) {
  return argv.find((a) => !a.startsWith('-')) === 'doctor';
}

/** The same arguments without that leading command word, so it is not handed to the server twice. */
export function withoutCommand(argv) {
  const at = argv.findIndex((a) => !a.startsWith('-'));
  return at === -1 ? argv : [...argv.slice(0, at), ...argv.slice(at + 1)];
}

/**
 * Whether to run the `doctor` preflight before starting.
 *
 * Only in `nx` mode, and for one reason: the Nx TUI is about to take the terminal, so a blocking problem must be said
 * in plain scrollback first. The managed mode is the desktop apps, which have their own installer and show the same
 * facts in the first-run wizard, so they are left alone (and spared the extra process).
 *
 * `--skip-doctor` / `CODAY_SKIP_DOCTOR=1` bypasses it, for anyone who knows better than the checks.
 */
export function shouldRunDoctor({ mode, argv, env }) {
  if (mode !== 'nx') return false;
  if (hasFlag(argv, '--skip-doctor')) return false;
  return !(env.CODAY_SKIP_DOCTOR === '1' || env.CODAY_SKIP_DOCTOR === 'true');
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

/**
 * Arguments of `nx run`: ONE target, `coday:status`, whose continuous dependencies are the server, AgentOS and the
 * Factory (each gets its own TUI pane). A single-target run makes the Nx TUI pin the initiating task (`status`) and
 * its first continuous dependency (`server`) at startup; `run-many` pins nothing. The target names are those of
 * NX_ENTRY_TASK / NX_SERVICES in apps/server/src/lib/runtime/workspace.ts (a server spec checks they match).
 * `--parallel` only caps discrete tasks (`provision`): continuous tasks have their own threads.
 */
export function nxArgs() {
  return ['run', 'coday:status', '--parallel=5', '--outputStyle=tui'];
}

/** Environment of the Nx process: no daemon, no cloud, no cache. */
export function nxEnv(env, configDir) {
  return {
    ...env,
    NX_DAEMON: 'false',
    NX_NO_CLOUD: 'true',
    NX_SKIP_NX_CACHE: 'true',
    NX_TUI: 'true',
    NX_WORKSPACE_DATA_DIRECTORY: join(configDir, 'runtime', '.nx'),
    NX_CACHE_DIRECTORY: join(configDir, 'runtime', '.nx', 'cache'),
  };
}

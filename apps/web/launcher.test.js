import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'path';
import {
  chooseMode,
  nxArgs,
  nxEnv,
  parseWorkspaceDir,
  resolveConfigDir,
  serverArgs,
  shouldRunDoctor,
  wantsDoctorOnly,
  withoutCommand,
} from './launcher.js';

test('interactive terminal runs through nx', () => {
  assert.equal(chooseMode({ argv: [], env: {}, isTTY: true }).mode, 'nx');
});

test('no TTY (desktop apps) stays on the in-process managed mode', () => {
  assert.equal(chooseMode({ argv: [], env: {}, isTTY: false }).mode, 'managed');
  assert.equal(chooseMode({ argv: [], env: {}, isTTY: undefined }).mode, 'managed');
});

test('--managed and CODAY_NO_NX force the managed mode even on a TTY', () => {
  assert.equal(chooseMode({ argv: ['--managed'], env: {}, isTTY: true }).mode, 'managed');
  assert.equal(chooseMode({ argv: [], env: { CODAY_NO_NX: '1' }, isTTY: true }).mode, 'managed');
  assert.equal(chooseMode({ argv: [], env: { CODAY_NO_NX: 'true' }, isTTY: true }).mode, 'managed');
  assert.equal(chooseMode({ argv: [], env: { CODAY_NO_NX: '0' }, isTTY: true }).mode, 'nx');
});

test('configDir: flag forms and default', () => {
  assert.equal(resolveConfigDir(['--configDir', '/a'], '/h'), '/a');
  assert.equal(resolveConfigDir(['--auth', '--configDir=/b'], '/h'), '/b');
  assert.equal(resolveConfigDir(['--coday_config_dir', '/c'], '/h'), '/c');
  assert.equal(resolveConfigDir([], '/h'), '/h/.coday');
});

test('configDir: a relative directory is made absolute, Nx runs from <configDir>/runtime', () => {
  assert.equal(resolveConfigDir(['--configDir', './my-coday'], '/h'), resolve('my-coday'));
  assert.equal(resolveConfigDir(['--configDir=my-coday'], '/h'), resolve('my-coday'));
});

test('the launcher-only flags are not forwarded to the server', () => {
  assert.deepEqual(serverArgs(['--managed', '--auth', '--configDir', '/x']), ['--auth', '--configDir', '/x']);
  assert.deepEqual(serverArgs(['--skip-doctor', '--auth']), ['--auth']);
});

test('the preflight runs for an interactive start', () => {
  assert.equal(shouldRunDoctor({ mode: 'nx', argv: [], env: {} }), true);
});

test('the preflight is skipped in managed mode: the desktop apps have their own installer and wizard', () => {
  assert.equal(shouldRunDoctor({ mode: 'managed', argv: [], env: {} }), false);
});

test('the preflight can be bypassed by flag or environment, in both flag forms', () => {
  assert.equal(shouldRunDoctor({ mode: 'nx', argv: ['--skip-doctor'], env: {} }), false);
  assert.equal(shouldRunDoctor({ mode: 'nx', argv: ['--skip-doctor=true'], env: {} }), false);
  assert.equal(shouldRunDoctor({ mode: 'nx', argv: [], env: { CODAY_SKIP_DOCTOR: '1' } }), false);
  assert.equal(shouldRunDoctor({ mode: 'nx', argv: [], env: { CODAY_SKIP_DOCTOR: 'true' } }), false);
  assert.equal(shouldRunDoctor({ mode: 'nx', argv: [], env: { CODAY_SKIP_DOCTOR: '0' } }), true);
});

test('`coday-web doctor` is the preflight and nothing else, whatever the terminal', () => {
  assert.equal(wantsDoctorOnly(['doctor']), true);
  assert.equal(wantsDoctorOnly(['--configDir=/x', 'doctor']), true);
  assert.equal(wantsDoctorOnly([]), false);
  assert.equal(wantsDoctorOnly(['--skip-doctor']), false);
  assert.equal(wantsDoctorOnly(['status']), false);
});

test('the command word is not handed to the server twice', () => {
  assert.deepEqual(withoutCommand(['doctor', '--auth']), ['--auth']);
  assert.deepEqual(withoutCommand(['--configDir=/x', 'doctor', '--auth']), ['--configDir=/x', '--auth']);
  assert.deepEqual(withoutCommand(['--auth']), ['--auth']);
});

test('the workspace directory is read from the marker line, whatever else the server prints', () => {
  assert.equal(parseWorkspaceDir('CODAY_RUNTIME_WORKSPACE=/home/me/.coday/runtime\n'), '/home/me/.coday/runtime');
  assert.equal(
    parseWorkspaceDir('2026-10-31T09:29:25.306Z EXIT workspace finished with code 0\nCODAY_RUNTIME_WORKSPACE=/a b/runtime\nnoise\n'),
    '/a b/runtime'
  );
  assert.equal(parseWorkspaceDir('/home/me/.coday/runtime\n2026-10-31T09:29:25.306Z EXIT'), undefined);
  assert.equal(parseWorkspaceDir('CODAY_RUNTIME_WORKSPACE=\n'), undefined);
  assert.equal(parseWorkspaceDir(undefined), undefined);
});

test('nx runs ONE target, coday:status, whose dependencies are the services (single-target run pins status + server)', () => {
  const args = nxArgs();
  assert.deepEqual(args.slice(0, 2), ['run', 'coday:status']);
  assert.ok(!args.includes('run-many'), 'run-many pins no pane in the Nx TUI');
  assert.ok(args.includes('--outputStyle=tui'));
});

test('--parallel is accepted by `nx run` and cannot starve provision (the only discrete task)', () => {
  const parallel = Number(nxArgs().find((a) => a.startsWith('--parallel=')).split('=')[1]);
  // Continuous tasks have their own threads (task-orchestrator getThreadPoolSize): --parallel only caps discrete ones.
  assert.ok(parallel >= 1);
});

test('--no-open is not a launcher-only flag: it reaches the server and the Nx tasks', () => {
  assert.deepEqual(serverArgs(['--managed', '--no-open']), ['--no-open']);
});

test('nx env: no daemon, no cloud, no cache, state kept under configDir', () => {
  const env = nxEnv({ PATH: '/bin' }, '/cfg');
  assert.equal(env.NX_DAEMON, 'false');
  assert.equal(env.NX_NO_CLOUD, 'true');
  assert.equal(env.NX_SKIP_NX_CACHE, 'true');
  assert.equal(env.PATH, '/bin');
  assert.ok(env.NX_WORKSPACE_DATA_DIRECTORY.startsWith('/cfg'));
});

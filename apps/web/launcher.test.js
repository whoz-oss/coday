import test from 'node:test';
import assert from 'node:assert/strict';
import { chooseMode, nxArgs, nxEnv, parseWorkspaceDir, resolveConfigDir, serverArgs } from './launcher.js';

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

test('the launcher-only flag is not forwarded to the server', () => {
  assert.deepEqual(serverArgs(['--managed', '--auth', '--configDir', '/x']), ['--auth', '--configDir', '/x']);
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

test('nx runs the three continuous tasks of the coday project', () => {
  const args = nxArgs();
  assert.deepEqual(args.slice(0, 5), ['run-many', '-t', 'server', 'agentos', 'factory']);
  assert.ok(args.includes('coday'));
});

test('nx env: no daemon, no cloud, no cache, state kept under configDir', () => {
  const env = nxEnv({ PATH: '/bin' }, '/cfg');
  assert.equal(env.NX_DAEMON, 'false');
  assert.equal(env.NX_NO_CLOUD, 'true');
  assert.equal(env.NX_SKIP_NX_CACHE, 'true');
  assert.equal(env.PATH, '/bin');
  assert.ok(env.NX_WORKSPACE_DATA_DIRECTORY.startsWith('/cfg'));
});

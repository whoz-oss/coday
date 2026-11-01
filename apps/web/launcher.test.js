import test from 'node:test';
import assert from 'node:assert/strict';
import {
  chooseMode,
  nxArgs,
  nxEnv,
  parseWorkspaceDir,
  resolveConfigDir,
  serverArgs,
  shouldOpenBrowser,
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

test('the launcher-only flags are not forwarded to the server', () => {
  assert.deepEqual(serverArgs(['--no-open', '--managed', '--auth']), ['--auth']);
  assert.deepEqual(serverArgs(['--configDir', '/x', '--no-open']), ['--configDir', '/x']);
});

test('nx runs the four continuous tasks of the coday project, within the parallelism limit', () => {
  const args = nxArgs();
  assert.deepEqual(args.slice(0, 6), ['run-many', '-t', 'server', 'agentos', 'factory', 'status']);
  assert.ok(args.includes('coday'));
  // provision is a task too: 4 continuous tasks + provision must fit.
  const parallel = Number(args.find((a) => a.startsWith('--parallel=')).split('=')[1]);
  assert.ok(parallel >= 5);
});

test('the browser opens by default in nx mode', () => {
  assert.equal(nxEnv({}, '/cfg', []).CODAY_OPEN_BROWSER, '1');
  assert.equal(nxEnv({}, '/cfg').CODAY_OPEN_BROWSER, '1');
  assert.equal(nxEnv({}, '/cfg', ['--auth']).CODAY_OPEN_BROWSER, '1');
});

test('--no-open and CODAY_NO_OPEN=1|true keep the browser closed', () => {
  assert.equal(nxEnv({}, '/cfg', ['--no-open']).CODAY_OPEN_BROWSER, undefined);
  assert.equal(nxEnv({ CODAY_NO_OPEN: '1' }, '/cfg', []).CODAY_OPEN_BROWSER, undefined);
  assert.equal(nxEnv({ CODAY_NO_OPEN: 'true' }, '/cfg', []).CODAY_OPEN_BROWSER, undefined);
  assert.equal(nxEnv({ CODAY_NO_OPEN: '0' }, '/cfg', []).CODAY_OPEN_BROWSER, '1');
  assert.equal(shouldOpenBrowser([], { CODAY_NO_OPEN: 'false' }), true);
});

test('a CODAY_OPEN_BROWSER inherited from the caller is not trusted: only the launcher decides', () => {
  assert.equal(nxEnv({ CODAY_OPEN_BROWSER: '1' }, '/cfg', ['--no-open']).CODAY_OPEN_BROWSER, undefined);
});

test('nx env: no daemon, no cloud, no cache, state kept under configDir', () => {
  const env = nxEnv({ PATH: '/bin' }, '/cfg');
  assert.equal(env.NX_DAEMON, 'false');
  assert.equal(env.NX_NO_CLOUD, 'true');
  assert.equal(env.NX_SKIP_NX_CACHE, 'true');
  assert.equal(env.PATH, '/bin');
  assert.ok(env.NX_WORKSPACE_DATA_DIRECTORY.startsWith('/cfg'));
});

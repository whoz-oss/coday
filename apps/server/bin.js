#!/usr/bin/env node
import { spawn } from 'child_process';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

/**
 * `coday-server [provision|agentos|factory|server] [--configDir <dir>] ...`
 *
 * Runs the compiled server bundle with the given arguments. Without a command it runs everything in
 * one process (Express + AgentOS + Factory). The Nx tasks of the generated runtime workspace call
 * the commands (see apps/web).
 */
const serverPath = resolve(__dirname, 'server.js');
const args = process.argv.slice(2);

// No shell: the arguments (paths with spaces) must reach the server untouched.
const child = spawn(process.execPath, [serverPath, ...args], {
  stdio: 'inherit',
});

for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(signal, () => child.kill(signal));
}

child.on('exit', (code) => {
  process.exit(code || 0);
});

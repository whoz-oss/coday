#!/usr/bin/env node
import { spawn } from 'child_process';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const serverPath = resolve(__dirname, 'server/server.ts');
const args = process.argv.slice(2);

// Run the server using tsx
const child = spawn('tsx', [serverPath, ...args], {
  stdio: 'inherit',
  shell: true
});

child.on('exit', (code) => {
  process.exit(code || 0);
});

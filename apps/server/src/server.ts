/**
 * Entry point of the bundled Coday web server (`server.js`).
 *
 * The first positional argument selects what runs (see lib/runtime/commands.ts):
 *   doctor                                   preflight: Node, Java, config dir, ripgrep, ports (exit 1 when blocked)
 *   provision | agentos | factory | server   the Nx tasks of the generated runtime workspace
 *   (nothing)                                everything in this one process: Express + AgentOS + Factory,
 *                                            the mode the desktop apps and `CODAY_NO_NX=1` rely on.
 *
 * All the logic is in lib/: web-server.ts (Express app), lib/runtime (provisioning, orchestration),
 * lib/setup (first-run wizard API).
 */

import { debugLog } from './lib/log'
import { parseCommand, runCommand } from './lib/runtime/commands'
import { hideBin } from 'yargs/helpers'

const command = parseCommand(hideBin(process.argv))

// Handle uncaught exceptions to prevent hanging
process.on('uncaughtException', (error) => {
  console.error('Uncaught exception:', error)
  process.exit(1)
})

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled rejection at:', promise, 'reason:', reason)

  // Handle timeout and abort errors gracefully - these are expected during high load
  if (reason && typeof reason === 'object') {
    const error = reason as { name?: string; message?: string }
    if (error.name === 'TimeoutError' || error.message?.includes('timeout')) {
      console.log('Detected timeout error - handling gracefully without shutdown')
      return
    }
    if (error.name === 'AbortError' || error.message?.includes('aborted')) {
      console.log('Detected abort error - handling gracefully without shutdown')
      return
    }
  }
  console.error('Critical unhandled rejection detected')
  process.exit(1)
})

runCommand(command)
  .then((code) => {
    // One-shots whose stdout is read as it is: `workspace` by the launcher, `doctor` by the user.
    if (command !== 'workspace' && command !== 'doctor')
      debugLog('EXIT', `${command ?? 'managed'} finished with code ${code}`)
    process.exit(code)
  })
  .catch((error) => {
    console.error('Failed to start server:', error)
    process.exit(1)
  })

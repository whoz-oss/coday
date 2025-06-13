import { Coday } from '@coday/core'
import { parseCodayOptions } from '@coday/options'
import { TerminalNonInteractiveInteractor } from './terminal-non-interactive-interactor'
import { TerminalInteractor } from './terminal-interactor'
import * as os from 'node:os'
import { UserService } from '@coday/service/user.service'
import { ProjectService } from '@coday/service/project.service'
import { IntegrationService } from '@coday/service/integration.service'
import { MemoryService } from '@coday/service/memory.service'
import { McpConfigService } from '@coday/service/mcp-config.service'
import { CodayLogger } from '@coday/service/coday-logger'

const options = parseCodayOptions()

const interactor = options.oneshot ? new TerminalNonInteractiveInteractor() : new TerminalInteractor()

// Get the username and build the userConfigService from it
const username = os.userInfo().username
const user = new UserService(options.configDir, username, interactor)
const project = new ProjectService(interactor, options.configDir)
const integration = new IntegrationService(project, user)
const memory = new MemoryService(project, user)
const mcp = new McpConfigService(user, project, interactor)
// Logging is enabled when --log flag is used and not in no-auth mode
const loggingEnabled = options.log && !options.noAuth
const logger = new CodayLogger(loggingEnabled, options.logFolder)

const coday = new Coday(interactor, options, {
  user,
  project,
  integration,
  memory,
  mcp,
  logger: logger,
})

// Setup comprehensive cleanup for all termination scenarios
if (interactor instanceof TerminalInteractor) {
  // Normal process exit - cleanup interactor only
  process.on('exit', () => {
    interactor.cleanup()
  })

  // Forced termination (Ctrl+C, SIGTERM) - full cleanup
  const handleForcedTermination = async (signal: string) => {
    console.log(`\nReceived ${signal}, cleaning up...`)
    try {
      coday.kill().then(() => {
        interactor.cleanup()
      })
    } catch (error) {
      console.error('Error during cleanup:', error)
    } finally {
      process.exit(0)
    }
  }

  process.on('SIGINT', () => handleForcedTermination('SIGINT'))
  process.on('SIGTERM', () => handleForcedTermination('SIGTERM'))
}

// Run Coday
coday.run().catch((error) => {
  console.error('Coday run failed:', error)
  process.exit(1)
})

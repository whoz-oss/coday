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
const user = new UserService(options.configDir, username)
const project = new ProjectService(interactor, options.configDir)
const integration = new IntegrationService(project, user)
const memory = new MemoryService(project, user)
const mcp = new McpConfigService(user, project, interactor)
// Logging is enabled when --log flag is used and not in no-auth mode
const loggingEnabled = options.log && !options.noAuth
const logger = new CodayLogger(loggingEnabled, options.logFolder)

// Setup cleanup for terminal interactor when process exits
if (interactor instanceof TerminalInteractor) {
  // Clean up readline interface on exit
  process.on('exit', () => {
    interactor.cleanup()
  })

  // Let Node.js handle Ctrl+C normally
  process.on('SIGINT', () => {
    console.log('\nExiting...')
    process.exit(0)
  })
}

new Coday(interactor, options, {
  user,
  project,
  integration,
  memory,
  mcp,
  logger: logger,
}).run()

import { Coday } from '@coday/core'
import { parseCodayOptions } from '@coday/options'
import { TerminalNonInteractiveInteractor } from './terminal-non-interactive-interactor'
import { TerminalInteractor } from './terminal-interactor'
import * as os from 'node:os'
import { UserService } from './service/user.service'
import { ProjectService } from './service/project.service'
import { IntegrationService } from './service/integration.service'
import { MemoryService } from './service/memory.service'

const options = parseCodayOptions()

const interactor = options.oneshot ? new TerminalNonInteractiveInteractor() : new TerminalInteractor()

// Get the username and build the userConfigService from it
const username = os.userInfo().username
const user = new UserService(options.configDir, username)
const project = new ProjectService(interactor, options.configDir)
const integration = new IntegrationService(project, user)
const memory = new MemoryService(project, user)

new Coday(interactor, options, {
  user,
  project,
  integration,
  memory,
}).run()

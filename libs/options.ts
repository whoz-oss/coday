import * as path from 'path'
import * as os from 'os'
import yargs from 'yargs'
import { hideBin } from 'yargs/helpers'

// Define the argv type
export interface Argv {
  coday_project?: string
  coday_config_dir?: string
  auth?: boolean
  prompt?: string[]
  oneshot?: boolean
  debug?: boolean
  fileReadOnly?: boolean
  local?: boolean
  multi?: boolean
  agentFolders?: string[]
  log?: boolean
  log_folder?: string
  _: (string | number)[]
  $0: string
}

// Define the options type
export interface CodayOptions {
  oneshot: boolean
  debug: boolean
  project?: string
  thread?: string
  prompts: string[]
  fileReadOnly: boolean
  configDir: string // Always defined with default value
  auth: boolean
  agentFolders: string[]
  noLog: boolean
  logFolder?: string
  forcedProject: boolean // true if --local is used
}

/**
 * Parse command line arguments and return Coday options
 * @returns CodayOptions object
 */
export function parseCodayOptions(): CodayOptions {
  // Parse arguments
  const args = hideBin(process.argv)
  const argv: Argv = yargs(args)
    .option('coday_project', {
      type: 'string',
      description: 'Project name',
    })
    .option('prompt', {
      type: 'array',
      description: 'Prompt(s) to execute',
    })
    .option('oneshot', {
      type: 'boolean',
      description: 'Run in one-shot mode (non-interactive)',
    })
    .option('auth', {
      type: 'boolean',
      description: 'Enables web auth check (expects x-forwarded-email header from auth proxy)',
    })
    .option('local', {
      type: 'boolean',
      description: 'Use current directory as sole project (restricted mode)',
    })
    .option('multi', {
      type: 'boolean',
      description: 'Multi-project mode: show project list without default selection',
    })
    .option('fileReadOnly', {
      type: 'boolean',
      description: 'Run in read-only mode for files (no write/delete operations)',
    })
    .option('configDir', {
      type: 'string',
      description: 'Path to the local .coday config dir',
    })
    .option('agentFolders', {
      type: 'array',
      description: 'Additional folders where agent definitions can be found',
      alias: 'af',
    })
    .option('log', {
      type: 'boolean',
      description: 'Enable logging (use --no-log to disable)',
      default: true,
    })
    .option('log-folder', {
      type: 'string',
      description: 'Custom folder for log files (defaults to ~/.coday/logs)',
    })
    .option('debug', {
      type: 'boolean',
      description: 'Sets debug right at startup',
    })
    .help().argv as Argv
  let projectName: string | undefined
  let forcedProject: boolean

  const prompts: string[] = (argv.prompt || argv._.slice(1)) as string[]
  const oneshot: boolean = !!argv.oneshot
  const fileReadOnly: boolean = !!argv.fileReadOnly

  // Set configDir with default value
  const defaultConfigDir = path.join(os.homedir(), '.coday')
  const configDir: string = argv.coday_config_dir || defaultConfigDir

  const auth: boolean = !!argv.auth
  const debug: boolean = !!argv.debug
  const noLog: boolean = !argv.log // Inverted: log=false means noLog=true
  const logFolder: string | undefined = argv.log_folder

  // Determine project selection mode
  if (argv.coday_project) {
    // Project direct selection has precedence on all other
    projectName = argv.coday_project
    forcedProject = true
    console.log(`Project mode: restricting to project '${projectName}'`)
  } else if (argv.local) {
    // --local: force current directory as ONLY project (restricted mode)
    projectName = path.basename(process.cwd())
    forcedProject = true
    console.log(`Local mode: restricting to project '${projectName}'`)
  } else if (argv.multi) {
    // --multi: traditional mode, no default project
    projectName = ''
    forcedProject = false
    console.log('Multi-project mode: no default project selection')
  } else {
    // Default behavior: use current directory as default project
    projectName = path.basename(process.cwd())
    forcedProject = false
    console.log(`Default mode: using '${projectName}' as default project`)
  }

  return {
    oneshot,
    debug,
    project: projectName,
    prompts,
    fileReadOnly,
    configDir,
    auth,
    agentFolders: (argv.agentFolders || []) as string[],
    noLog,
    logFolder,
    forcedProject,
  }
}

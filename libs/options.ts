import * as path from 'path'
import yargs from 'yargs'
import { hideBin } from 'yargs/helpers'

// Define the argv type
export interface Argv {
  coday_project?: string
  coday_config_dir?: string
  no_auth?: boolean
  prompt?: string[]
  oneshot?: boolean
  debug?: boolean
  fileReadOnly?: boolean
  local?: boolean
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
  prompts: string[]
  fileReadOnly: boolean
  configDir?: string
  noAuth: boolean
  agentFolders: string[]
  noLog: boolean
  logFolder?: string
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
    .option('no auth', {
      type: 'boolean',
      description: 'Disables web auth check',
    })
    .option('local', {
      type: 'boolean',
      description: 'Use current directory name as project name',
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
  let projectName: string | undefined = argv.coday_project || (argv._[0] as string)
  const prompts: string[] = (argv.prompt || argv._.slice(1)) as string[]
  const oneshot: boolean = !!argv.oneshot
  const fileReadOnly: boolean = !!argv.fileReadOnly
  const configDir: string | undefined = argv.coday_config_dir
  const noAuth: boolean = !!argv.no_auth
  const debug: boolean = !!argv.debug
  const noLog: boolean = !argv.log // Inverted: log=false means noLog=true
  const logFolder: string | undefined = argv.log_folder

  // If --local is set, use current directory name as project
  if (argv.local) {
    projectName = path.basename(process.cwd())
    console.log(`Using current directory name as project: ${projectName}`)
  }

  return {
    oneshot,
    debug,
    project: projectName,
    prompts,
    fileReadOnly,
    configDir,
    noAuth,
    agentFolders: (argv.agentFolders || []) as string[],
    noLog,
    logFolder,
  }
}

import yargs from 'yargs'
import { hideBin } from 'yargs/helpers'

// Define the argv type
export interface Argv {
  coday_project?: string
  coday_config_dir?: string
  no_auth?: boolean
  prompt?: string[]
  oneshot?: boolean
  fileReadOnly?: boolean
  _: (string | number)[]
  $0: string
}

// Define the options type
export interface CodayOptions {
  oneshot: boolean
  project?: string
  prompts: string[]
  fileReadOnly: boolean
  configDir?: string
  noAuth: boolean
}

/**
 * Parse command line arguments and return Coday options
 * @returns CodayOptions object
 */
export function parseCodayOptions(): CodayOptions {
  // Parse arguments
  const args = hideBin(process.argv)
  console.log('raw args', args)
  const argv: Argv = yargs(args)
    .option('project', {
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
    .option('fileReadOnly', {
      type: 'boolean',
      description: 'Run in read-only mode for files (no write/delete operations)',
    })
    .option('configDir', {
      type: 'string',
      description: 'Path to the local .coday config dir',
    })
    .help().argv as Argv

  const projectName: string | undefined = argv.coday_project || (argv._[0] as string)
  const prompts: string[] = (argv.prompt || argv._.slice(1)) as string[]
  const oneshot: boolean = !!argv.oneshot
  const fileReadOnly: boolean = !!argv.fileReadOnly
  const configDir: string | undefined = argv.coday_config_dir
  const noAuth: boolean = !!argv.no_auth

  return {
    oneshot,
    project: projectName,
    prompts,
    fileReadOnly,
    configDir,
    noAuth,
  }
}

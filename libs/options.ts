import yargs from 'yargs'
import { hideBin } from 'yargs/helpers'

// Define the argv type
export interface Argv {
  coday_project?: string
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
    .option('fileReadOnly', {
      type: 'boolean',
      description: 'Run in read-only mode for files (no write/delete operations)',
    })
    .help().argv as Argv

  const projectName = argv.coday_project || (argv._[0] as string)
  const prompts = (argv.prompt || argv._.slice(1)) as string[]
  const oneshot = !!argv.oneshot
  const fileReadOnly = !!argv.fileReadOnly

  return {
    oneshot,
    project: projectName,
    prompts,
    fileReadOnly,
  }
}

import path from 'path'
import yargs from 'yargs'
import { hideBin } from 'yargs/helpers'
import os from 'node:os'

// Define the argv type
export interface Argv {
  auth?: boolean
  configDir?: string
  coday_config_dir?: string
  host?: string
  _: (string | number)[]
  $0: string
}

/**
 * Runtime options needed by the Coday web server.
 *
 * The legacy agent-runtime options (project selection, prompts, one-shot mode,
 * logging, agent folders...) were removed together with the thread-based Coday
 * engine — only what the proxy server itself needs remains.
 */
export interface CodayOptions {
  /** Root config directory (defaults to ~/.coday) — hosts the managed AgentOS/Factory JARs. */
  configDir: string
  /** Whether web auth (x-forwarded-email / Cloudflare Access) is enforced. */
  auth: boolean
  /**
   * Interface Express listens on. Explicit `--host` / `CODAY_HOST` wins; otherwise loopback, except
   * for `--auth` deployments (behind a reverse proxy, possibly in a container) which listen on all
   * interfaces as they always did.
   */
  host: string
}

/**
 * Parse command line arguments and return Coday options
 *
 * For complete documentation of all command line options, see:
 * docs/02-getting-started/launching.md
 *
 * @returns CodayOptions object
 */
export function parseCodayOptions(
  rawArgs: string[] = hideBin(process.argv),
  env: NodeJS.ProcessEnv = process.env
): CodayOptions {
  // Parse arguments
  const argv: Argv = yargs(rawArgs)
    .option('auth', {
      type: 'boolean',
      description: 'Enables web auth check (expects x-forwarded-email header from auth proxy)',
    })
    .option('configDir', {
      type: 'string',
      description: 'Path to the local .coday config dir',
    })
    .option('host', {
      type: 'string',
      description: 'Interface to listen on (default 127.0.0.1; 0.0.0.0 with --auth). Env: CODAY_HOST',
    })
    .help().argv as Argv

  // Set configDir with default value
  const defaultConfigDir = path.join(os.homedir(), '.coday')
  const configDir: string = argv.configDir || argv.coday_config_dir || defaultConfigDir

  const auth: boolean = !!argv.auth
  const host = argv.host || env.CODAY_HOST || (auth ? '0.0.0.0' : '127.0.0.1')

  return {
    configDir,
    auth,
    host,
  }
}

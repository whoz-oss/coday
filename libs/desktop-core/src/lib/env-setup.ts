import { log } from './logger'

/**
 * Build a copy of process.env with the PATH enriched by resolving it through
 * a zsh login shell (so ~/.zprofile and Homebrew paths are included) and
 * appending common Homebrew prefix directories as a fallback.
 */
export function setupEnv(): NodeJS.ProcessEnv {
  const { execSync } = require('child_process') as typeof import('child_process')
  const env = { ...process.env }
  try {
    const userPath = execSync('/usr/bin/env echo $PATH', { shell: '/bin/zsh' }).toString().trim()
    env['PATH'] = `${userPath}:/usr/local/bin:/opt/homebrew/bin:${process.env['PATH']}`
  } catch {
    env['PATH'] = '/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin'
  }
  log('INFO', 'PATH resolved:', env['PATH'])
  return env
}

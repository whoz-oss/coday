import { Interactor } from '@coday/model'
import { exec } from 'child_process'
import { promisify } from 'util'
import * as path from 'path'
import { DEFAULT_CHAR_LIMIT, DEFAULT_LINE_LIMIT, limitOutput } from '@coday/utils'

const execAsync = promisify(exec)

const DEFAULT_MAX_BUFFER = 10 * 1024 * 1024 // 10MB buffer

export const runBash = async ({
  command,
  relPath,
  root,
  requireConfirmation,
  interactor,
  lineLimit = DEFAULT_LINE_LIMIT,
  maxBuffer = DEFAULT_MAX_BUFFER,
}: {
  command: string
  relPath?: string
  root: string
  requireConfirmation?: boolean
  interactor: Interactor
  lineLimit?: number
  maxBuffer?: number
}): Promise<string> => {
  const limits = { lineLimit, charLimit: DEFAULT_CHAR_LIMIT }
  const resolvedRoot = path.resolve(root)
  // Absolute, normalized execution directory (also reported back to the caller)
  const resolvedPath = path.resolve(resolvedRoot, relPath ?? '.')

  // Check if the resolved path is within the root folder to prevent leaving the designated folder
  const relative = path.relative(resolvedRoot, resolvedPath)
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    return 'Attempt to navigate outside the root folder'
  }

  // Trailer giving the execution context (pwd) of the command, appended to every executed command's output
  const workingDirectory = `\n\nWorking directory: ${resolvedPath}`

  try {
    // Log the command that will run
    interactor.debug(`\nRunning command: ${command} in ${resolvedPath}`)

    // If confirmation is required, ask for it
    if (requireConfirmation) {
      const answer = await interactor.chooseOption(
        ['OK', 'Cancel'],
        `Confirm execution of: \`${command}\``,
        undefined,
        true
      )
      if (answer !== 'OK') {
        const reason = answer && answer !== 'Cancel' ? ` with reason: ${answer}` : ''
        return `Command execution was cancelled by user${reason}`
      }
    }

    // Execute the command with a custom buffer size
    const { stdout, stderr } = await execAsync(command, {
      cwd: resolvedPath,
      maxBuffer,
    })

    const limitedOutput = limitOutput(stdout, limits)
    const limitedErr = limitOutput(stderr, limits)
    // stderr on a successful command is informational (progress, warnings) — not an error
    return `Output:\n${limitedOutput}${limitedErr ? `\n\nStderr:\n${limitedErr}` : ''}${workingDirectory}`
  } catch (error: any) {
    const stdout = error.stdout ? `\nStdout:\n${limitOutput(error.stdout, limits)}` : ''
    const stderr = error.stderr ? `\nStderr:\n${limitOutput(error.stderr, limits)}` : ''
    const message = `Command failed: ${error.message ?? error}${stdout}${stderr}${workingDirectory}`
    interactor.error(message)
    return message
  }
}

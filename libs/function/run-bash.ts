import { Interactor } from '../model'
import { exec } from 'child_process'
import { promisify } from 'util'
import * as path from 'path'
import { DEFAULT_CHAR_LIMIT, DEFAULT_LINE_LIMIT, limitOutput } from '../utils/output-limiter'

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
  let resolvedPath = root
  const limits = { lineLimit, charLimit: DEFAULT_CHAR_LIMIT }

  if (relPath) {
    resolvedPath = path.resolve(root, relPath)
  }

  // Check if the resolved path is within the root folder to prevent leaving the designated folder
  if (!resolvedPath.startsWith(path.resolve(root))) {
    return 'Attempt to navigate outside the root folder'
  }

  try {
    // Resolve the absolute path from root and relPath
    const resolvedPath = relPath ? path.resolve(root, relPath) : root

    // Log the command that will run
    interactor.displayText(`\nRunning command: ${command} in ${resolvedPath}`)

    // If confirmation is required, ask for it
    if (requireConfirmation) {
      const rejectReason = await interactor.promptText(
        `\nPlease type the reason to reject this command (nothing = validate)`
      )
      if (rejectReason) {
        interactor.displayText('Command execution was cancelled by user.')
        return `Command execution was cancelled by user with following reason: ${rejectReason}`
      }
    }

    // Execute the command with a custom buffer size
    const { stdout, stderr } = await execAsync(command, {
      cwd: resolvedPath,
      maxBuffer,
    })

    const limitedOutput = limitOutput(stdout, limits)
    const limitedErr = limitOutput(stderr, limits)
    return `Output:\n${limitedOutput}${limitedErr ? `\n\nError:\n${limitedErr}` : ''}`
  } catch (error: any) {
    const stdout = error.stdout ? `\nstdout: ${limitOutput(error.stdout, limits)}` : ''
    const stderr = error.stderr ? `\nstderr: ${limitOutput(error.stderr, limits)}` : ''
    const message = `An error occurred while executing the command: ${error}${stdout}${stderr}`
    interactor.error(message)
    return message
  }
}

import {Interactor} from "../interactor";
import {exec} from "child_process";
import {promisify} from "util"
import * as path from "path"

const execAsync = promisify(exec)

const DEFAULT_LINE_LIMIT = 1000
const DEFAULT_MAX_BUFFER = 10 * 1024 * 1024; // 10MB buffer

const limitOutputLines = (output: string, limit: number): string => {
    const lines = output.split("\n")
    if (lines.length > limit) {
        return lines.slice(-limit).join("\n")
    }
    return output
};

export const runBash = async ({
                                  command,
                                  relPath,
                                  root,
                                  requireConfirmation,
                                  interactor,
                                  lineLimit = DEFAULT_LINE_LIMIT,
                                  maxBuffer=DEFAULT_MAX_BUFFER
                              }: {
    command: string,
    relPath?: string,
    root: string,
    requireConfirmation?: boolean,
    interactor: Interactor,
    lineLimit?: number,
    maxBuffer?: number
}): Promise<string> => {
    let resolvedPath = root

    if (relPath) {
        resolvedPath = path.resolve(root, relPath)
    }

    // Check if the resolved path is within the root folder to prevent leaving the designated folder
    if (!resolvedPath.startsWith(path.resolve(root))) {
        return "Attempt to navigate outside the root folder"
    }

    try {
        // Resolve the absolute path from root and relPath
        const resolvedPath = relPath ? path.resolve(root, relPath) : root

        // Log the command that will run
        interactor.displayText(`Running command: ${command} in ${resolvedPath}`)

        // If confirmation is required, ask for it
        if (requireConfirmation) {
            const rejectReason = interactor.promptText(
                `Please type the reason to reject this command (nothing = validate)`
            )
            if (rejectReason) {
                interactor.displayText("Command execution was cancelled by user.")
                return `Command execution was cancelled by user with following reason: ${rejectReason}`
            }
        }

        // Execute the command with a custom buffer size
        const {stdout, stderr} = await execAsync(command, {
            cwd: resolvedPath,
            maxBuffer
        })

        const limitedOutput = limitOutputLines(stdout, lineLimit)
        const limitedErr = limitOutputLines(stderr, lineLimit)
        return `${limitedOutput}\n${limitedErr}`
    } catch (error) {
        interactor.error(`An error occurred while executing the command: ${error}`)
        return `Got an error: ${error}`
    }
}

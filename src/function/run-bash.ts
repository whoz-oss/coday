import {Interactor} from "../interactor";
import {exec} from 'child_process';
import {promisify} from 'util';
import * as path from 'path';

const execAsync = promisify(exec);

type RunBashInput = {
    command: string,
    relPath?: string,  // relative path
    root: string,
    requireConfirmation?: boolean,
    interactor: Interactor
};

export const runBash = async ({ command, relPath, root, requireConfirmation, interactor }: RunBashInput): Promise<string> => {
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
        const resolvedPath = relPath ? path.resolve(root, relPath): root


        // Log the command that will run
        interactor.displayText(`Running command: ${command}`);

        // If confirmation is required, ask for it
        if (requireConfirmation) {
            const confirmation = interactor.promptText(`Would you like to proceed with running the command: ? (type anything to abort)`);
            if (!confirmation) {
                interactor.displayText('Command execution was cancelled by user.');
                return 'Command execution was cancelled by user.';
            }
        }

        // Execute the command
        const { stdout, stderr } = await execAsync(command, { cwd: resolvedPath });

        // Return the combined stdout and stderr
        return `${stdout}${stderr}`;
    } catch (error) {
        interactor.error(`An error occurred while executing the command: ${error}`);
        throw error;
    }
};

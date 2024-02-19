import {CommandHandler} from "./command-handler";
import {simpleGit, SimpleGit, SimpleGitOptions} from "simple-git";
import {CommandContext} from "./command-context";
import * as readlineSync from 'readline-sync';

const keyCommand: string = "git"
const rootKey: string = "remotes/origin/"
const releaseKey: string = `${rootKey}release`
const serviceKey: string = `${rootKey}service`
const masterKey: string = `${rootKey}master`
const solveConflictSubcommand: string = "solve-conflict"

const envBranches = ["integration", "release-candidate", "current-release", "multi", "secops"]

export class GitBranchHandler extends CommandHandler {
    commandWord: string = "git"
    description: string = "wrapper for ai-git operations"

    accept(command: string, context: CommandContext): boolean {
        return command.startsWith(keyCommand)
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const subCommand = command.slice(keyCommand.length).trim()

        const options: Partial<SimpleGitOptions> = {
            baseDir: context.projectRootPath,
            binary: 'git',
            maxConcurrentProcesses: 2,
            trimmed: false
        }
        const git: SimpleGit = simpleGit(options)

        if (subCommand === "branch") {
            return this.branch(context, git)
        } else if (subCommand.startsWith(solveConflictSubcommand)) {
            const destinationBranch = subCommand.slice(solveConflictSubcommand.length).trim() || "integration"
            if (!envBranches.includes(destinationBranch)) {
                console.error(`Environment branch ${destinationBranch} is not in the known branches : ${envBranches.toString()}`)
                return context
            }

            return this.solveConflict(context, git, destinationBranch)
        } else {
            console.log("Available commands: branch")
        }
        return context
    }

    private async solveConflict(context: CommandContext, git: SimpleGit, destinationBranch: string): Promise<CommandContext> {
        try {
            // Check if the working directory is clean
            const status = await git.status();
            if (!status.isClean()) {
                console.error('Uncommitted changes detected. Please commit or stash them before running this script.');
                return context
            }

            // Store the current branch name in a variable
            const devBranch = (await git.branchLocal()).current;
            // Extract the last segment of the branch name
            const branchName = devBranch.split('/').pop() || devBranch;

            // Checkout the 'integration' branch from the remote and make sure it is up-to-date
            await git.fetch('origin', destinationBranch);
            await git.checkout(['-B', destinationBranch, `origin/${destinationBranch}`]);

            // Find the lowest positive integer N for which the new branch does not exist yet
            let N = 1;
            const username = context.username.replace(".", "")
            while (await git.revparse(['--verify', '--quiet', `solve-conflict/${username}/${destinationBranch}-${branchName}-${N}`])) {
                N++;
            }

            // Create and checkout the new branch
            const newBranch = `solve-conflict/${username}/${destinationBranch}-${branchName}-${N}`;
            await git.checkoutBranch(newBranch, destinationBranch);

            // Merge the initial branch into the current branch
            let error = undefined
            try {
                const mergeResult = await git.merge([devBranch]);
                // git.merge may not throw an error but a result with conflicts
                if (mergeResult.conflicts.length) {
                    error = mergeResult
                }

            } catch (err) {
                error = err
            }

            if (error) {
                console.error('Merge conflicts detected:', error);

                while (error) {
                    // Prompt the user for action
                    const answer = readlineSync.question('Please resolve conflicts before continuing (a=abort, enter to proceed)')

                    if (answer.toLowerCase() === "a") {
                        // Abort the merge
                        await git.merge(['--abort']);
                        error = undefined
                    } else {

                        // conflicts assumed resolved, commit & push, log branch and go back to devBranch
                        await git.add('.')

                        // commit
                        await git.commit('merge conflict resolved')
                        await git.push()
                    }
                }
            } else {
                // merge successful
                await git.push()
            }
            // Checkout the original dev branch
            await git.checkout(devBranch)
            console.log(`Returned to branch ${devBranch}.`);

        } catch (error) {
            console.error('An error occurred:', error);
        }
        return context
    }

    private async branch(context: CommandContext, git: SimpleGit): Promise<CommandContext> {
        // try to select the source branch
        const branch = await git.branch()
        console.log("Current branch: ", branch.current)
        console.log("")

        const releaseBranches = branch.all.filter(b => b.includes(releaseKey))
        const serviceBranches = branch.all.filter(b => b.includes(serviceKey))
        const masterBranches = branch.all.filter(b => b.includes(masterKey))
        const branches = [...masterBranches, ...releaseBranches, ...serviceBranches]

        // select source branch
        let sourceBranch: string | undefined = undefined
        if (!context.task) {
            console.warn(`No task data, select manually the relevant branch (from ${rootKey}):`)
            for (let i = 0; i < branches.length; i++) {
                console.log(`${i + 1} - ${branches[i].slice(rootKey.length)}`)
            }
            const sourceSelection = readlineSync.question("Type branch number (or else to abort):")
            try {
                const index = parseInt(sourceSelection) - 1
                if (!!index && index > 0) {
                    sourceBranch = branches[index]
                    console.log("Selected source branch:", sourceBranch)
                    return {...context, sourceBranch}
                }
            } catch (e) {
            }
            console.log("No source branch selected")
        } else {
            // todo: do something with context.task and chatGPT to select a branch
        }

        // define dev branch
        // only if not already on a dev branch: check current branch

        // go to dev branch
        return context
    }

}
import {CommandHandler} from "./command-handler";
import {simpleGit, SimpleGit, SimpleGitOptions} from "simple-git";
import {CommandContext} from "./command-context";
import * as readlineSync from 'readline-sync';

const keyCommand: string = "git"
const rootKey: string = "remotes/origin/"
const releaseKey: string = `${rootKey}release`
const serviceKey: string = `${rootKey}service`
const masterKey: string = `${rootKey}master`

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
        } else {
            console.log("Available commands: branch")
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

        if (!context.task) {
            console.warn(`No task data, select manually the relevant branch (from ${rootKey}):`)
            for (let i = 0; i < branches.length; i++) {
                console.log(`${i + 1} - ${branches[i].slice(rootKey.length)}`)
            }
            const sourceSelection = readlineSync.question("Type branch number (or else to abort):")
            try {
                const index = parseInt(sourceSelection)
                if (!!index && index > 0) {
                    const sourceBranch = branches[index]
                    console.log("Selected source branch:", sourceBranch)
                    return {...context, sourceBranch}
                }
            } catch (e) {
            }
            console.log("No source branch selected")
        } else {
            // do something with context.task and chatGPT to select a branch
        }
        return context
    }

}
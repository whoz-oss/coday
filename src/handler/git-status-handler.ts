import { runBash } from '../function/run-bash';
import { Interactor } from '../interactor';
import {CommandHandler} from "./command-handler";
import {CommandContext} from "../command-context";

export class GitStatusHandler extends CommandHandler {
    commandWord = 'status'
    description = 'gives a summary of files status regarding git'

    constructor(private interactor: Interactor) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const result = await runBash({
            command: 'git status',
            root: context.project.root,
            interactor: this.interactor,
        });
        this.interactor.displayText(result);
        return context
    }
}

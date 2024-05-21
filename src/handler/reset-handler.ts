import { CommandHandler } from "./command-handler";
import { CommandContext } from "../command-context";
import { Interactor } from "../interactor";

export class ResetHandler extends CommandHandler {
    commandWord = "reset";
    description = "Resets the context";
    private interactor: Interactor;

    constructor(interactor: Interactor) {
        super();
        this.interactor = interactor;
    }
    
    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        this.interactor.displayText('Context is being reset.');
        return {
            ...context,
            task: undefined,
            sourceBranch: undefined,
            commandQueue: [],
            // commandQueue: ["ai reset"],
            history: [],
        };
    }
}

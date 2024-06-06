import {CommandHandler} from "./command-handler";
import {runBash} from "../function/run-bash";
import {Interactor} from "../interactor";
import {CommandContext} from "../context";

export class RunBashHandler extends CommandHandler {
    commandWord = "run-bash";
    description = "Executes bash commands";

    constructor(private readonly interactor: Interactor) {
        super();
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const bashCommand = this.getSubCommand(command);

        if (!bashCommand) {
            this.interactor.error("No bash command provided.");
            return context;
        }

        try {
            const result = await runBash({
                command: bashCommand,
                root: context.project.root,
                interactor: this.interactor,
                requireConfirmation: false
            });
            this.interactor.displayText(result);
        } catch (error) {
            this.interactor.error(`Error executing bash command: ${error}`);
        }

        return context;
    }
}

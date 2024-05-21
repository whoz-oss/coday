import {CommandContext} from "../command-context";
import {CommandHandler} from "./command-handler";
import {Interactor} from "../interactor";
import {runBash} from "../function/run-bash";

export class TestHandler extends CommandHandler {
    commandWord: string = 'debug'
    description: string = 'run a command for dev-testing purposes'

    constructor(private interactor: Interactor) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const method = ({command}: {command: string}) => {
            return runBash({command, root: context.projectRootPath, interactor: this.interactor})
        }
        const args = {command: 'git status'}
        console.log("direct read")
        const result = await method(args)

        console.log(result)
        return context
    }

}
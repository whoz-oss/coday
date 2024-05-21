import {CommandContext} from "../command-context";
import {CommandHandler} from "./command-handler";
import {Interactor} from "../interactor";
import {findFilesByName} from "../function/find-files-by-name";

export class TestHandler extends CommandHandler {
    commandWord: string = 'debug'
    description: string = 'run a command for dev-testing purposes'

    constructor(private interactor: Interactor) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const method = ({text, path}: {text: string, path?: string}) => {
            return findFilesByName({text, path, root: context.projectRootPath, interactor: this.interactor})
        }
        const args = {text: 'tESt.ts'}
        console.log("direct read")
        const result = await method(args)

        console.log(result)
        return context
    }

}
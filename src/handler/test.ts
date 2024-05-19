import { CommandContext } from "../command-context";
import {CommandHandler} from "./command-handler";
import {readFileByPath} from "../function";
import {Interactor} from "../interactor";

export class TestHandler extends CommandHandler {
    commandWord: string = 'debug'
    description: string = 'run a command for dev-testing purposes'

    constructor(private interactor: Interactor) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const readProjectFile = ({path}: {path:string}) => {
            readFileByPath({path, root: context.projectRootPath, interactor: this.interactor})
        }
        const args = {path: 'index.ts'}
        console.log("direct read")
        readProjectFile(args)

        console.log("by apply")
        readProjectFile.apply(readProjectFile, [args])

        return context
    }

}
import {runBash} from "../function/run-bash"
import {Interactor} from "../interactor"
import {Beta} from "openai/resources"
import {Scripts} from "../service/scripts"
import {AssistantToolFactory, Tool} from "./init-tools"
import AssistantTool = Beta.AssistantTool
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {CommandContext} from "../command-context";

export class ScriptsTools extends AssistantToolFactory {

    constructor(interactor: Interactor) {
        super(interactor)
    }

    protected hasChanged(context: CommandContext): boolean {
        return this.lastToolInitContext?.project.scripts !== context.project.scripts
    }

    protected buildTools(context: CommandContext): Tool[] {
        const result: Tool[] = []

        const scripts: Scripts | undefined = context.project.scripts
        const scriptFunctions = scripts ?
            Object.entries(scripts).map(entry => {
                const script = async (params: {[param: string]: string}) => {
                    const commandWithParams = `${entry[1].command} ${Object.values(params).join(' ')}
                    return await runBash({
                        command: commandWithParams,
                        root: context.project.root,
                        interactor: this.interactor,
                        requireConfirmation: false
                    })
                }
                const scriptFunction: AssistantTool & RunnableToolFunction<{}> = {
                    type: "function",
                    function: {
                        name: entry[0],
                        description: entry[1].description,
                        parameters: {
                            type: "object",
                            properties: entry[1].parameters.reduce((acc, param) => {
                                acc[param] = { type: "string" }
                                return acc
                            }, {})
                        },
                        required: entry[1].parameters,
                        parse: JSON.parse,
                        function: script
                    }
                }
                return scriptFunction
            }) : []

        result.push(...scriptFunctions)

        return result
    }
}
import {runBash} from "../function/run-bash"
import {Interactor} from "../model/interactor"
import {Beta} from "openai/resources"
import {Scripts} from "../model/scripts"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {CommandContext} from "../model/command-context"
import AssistantTool = Beta.AssistantTool
import {AssistantToolFactory, Tool} from "./assistant-tool-factory";

const PARAMETERS: string = "PARAMETERS"

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
                const script = async (params: any) => {
                    let commandWithParams
                    if (entry[1].command.includes(PARAMETERS)) {
                        commandWithParams = entry[1].command.replace(PARAMETERS, params?.stringParameters ?? '')
                    } else {
                        commandWithParams = `${entry[1].command} ${params?.stringParameters ?? ''}`
                    }
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
                            properties: entry[1].parametersDescription ? { stringParameters: {type: "string", description: entry[1].parametersDescription}} : {}
                        },
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

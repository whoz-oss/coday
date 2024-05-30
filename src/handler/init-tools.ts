import {CommandContext} from "../command-context"
import {listFilesAndDirectories, readFileByPath, writeFile} from "../function"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {findFilesByName} from "../function/find-files-by-name"
import {Interactor} from "../interactor"
import {Beta} from "openai/resources"
import {Scripts} from "../service/scripts"
import AssistantTool = Beta.AssistantTool
import {runBash} from "../function/run-bash";

export type Tool = AssistantTool & RunnableToolFunction<any>

export abstract class AssistantToolFactory {
    tools: Tool[] = []
    lastToolInitContext: CommandContext | null = null
    protected constructor(protected interactor: Interactor) {}
    protected abstract hasChanged(context: CommandContext): boolean
    protected abstract buildTools(context: CommandContext): Tool[]

    getTools(context: CommandContext): Tool[] {
        if (!this.lastToolInitContext || this.hasChanged(context)) {
            this.lastToolInitContext = context
            this.tools = this.buildTools(context)
        }
        return this.tools
    }
}

export class OpenaiTools extends AssistantToolFactory {

    constructor(interactor: Interactor) {
        super(interactor)
    }

    protected hasChanged(context: CommandContext): boolean {
        return this.lastToolInitContext?.project.root !== context.project.root
    }

    protected buildTools(context: CommandContext): Tool[] {
        const result: Tool[] = []

        const readProjectFile = ({path}: { path: string }) => {
            return readFileByPath({relPath: path, root: context.project.root, interactor: this.interactor})
        }

        const readProjectFileFunction: AssistantTool & RunnableToolFunction<{ path: string }> = {
            type: "function",
            function: {
                name: "readProjectFile",
                description: "read the content of the file at the given path in the project. DO re-read with line to edit file is average size or more",
                parameters: {
                    type: "object",
                    properties: {
                        path: {type: "string", description: "file path relative to the project root (not exposed)"}
                    }
                },
                parse: JSON.parse,
                function: readProjectFile
            }
        }
        result.push(readProjectFileFunction)

        const writeProjectFile = ({path, content}: { path: string, content: string }) => {
            return writeFile({relPath: path, root: context.project.root, interactor: this.interactor, content})
        }

        const writeProjectFileFunction: AssistantTool & RunnableToolFunction<{ path: string, content: string }> = {
            type: "function",
            function: {
                name: "writeProjectFile",
                description: "write the content of the file at the given path in the project. IMPORTANT: the whole file is written, do not write it partially.",
                parameters: {
                    type: "object",
                    properties: {
                        path: {type: "string", description: "file path relative to the project root (not exposed)"},
                        content: {type: "string", description: "content of the file to write"}
                    }
                },
                parse: JSON.parse,
                function: writeProjectFile
            }
        }
        result.push(writeProjectFileFunction)

        const searchProjectFile = ({text, path}: { text: string, path?: string }) => {
            return findFilesByName({text, path, root: context.project.root, interactor: this.interactor})
        }

        const searchProjectFileFunction: AssistantTool & RunnableToolFunction<{ text: string, path?: string }> = {
            type: "function",
            function: {
                name: "searchProjectFile",
                description: "search in the project for files named starting by the given text. The output is a list of paths relative to the project root.",
                parameters: {
                    type: "object",
                    properties: {
                        text: {type: "string", description: "start of the name of files to search for"},
                        path: {
                            type: "string", description: "optional file path relative to the project root from which to start the search"}
                    }
                },
                parse: JSON.parse,
                function: searchProjectFile
            }
        }
        result.push(searchProjectFileFunction)

        const listProjectFilesAndDirectories = ({relPath}: { relPath: string }) => {
            return listFilesAndDirectories({relPath, root: context.project.root, interactor: this.interactor})
        }

        const listProjectFilesAndDirectoriesFunction: AssistantTool & RunnableToolFunction<{ relPath: string }> = {
            type: "function",
            function: {
                name: "listFilesAndDirectories",
                description: "list the directories and files in a folder (similar to the ls Unix command). Directories end with a slash.",
                parameters: {
                    type: "object",
                    properties: {
                        relPath: {type: "string", description: "path relative to the project root"}
                    }
                },
                parse: JSON.parse,
                function: listProjectFilesAndDirectories
            }
        }
        result.push(listProjectFilesAndDirectoriesFunction)

        const scripts: Scripts | undefined = context.project.scripts
        const scriptFunctions = scripts ?
            Object.entries(scripts).map(entry => {
                const script = async () => {
                    return await runBash({
                        command: entry[1].command,
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
                            properties: {}
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

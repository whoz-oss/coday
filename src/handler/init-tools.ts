import {CommandContext} from "../command-context"
import {listFilesAndDirectories, readFileByPath, writeFile} from "../function"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {findFilesByName} from "../function/find-files-by-name"
import {Interactor} from "../interactor"
import {Beta} from "openai/resources"
import AssistantTool = Beta.AssistantTool;

export type Tool = AssistantTool & RunnableToolFunction<any>

export class OpenaiTools {

    tools: Tool[] = []

    lastToolInitContext: CommandContext | null = null

    constructor(private interactor: Interactor) {
    }

    getTools(context: CommandContext): Tool[] {

        if (this.contextHasNotChanged(context)) {
            return this.tools
        }

        const readProjectFile = ({path}: { path: string }) => {
            return readFileByPath({relPath: path, root: context.projectRootPath, interactor: this.interactor})
        }

        const readProjectFileFunction: AssistantTool & RunnableToolFunction<{ path: string }> = {
            type: "function",
            function: {
                name: "readProjectFile",
                description: "read the content of the file at the given path in the project",
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

        const writeProjectFile = ({path, content}: { path: string, content: string }) => {
            return writeFile({relPath: path, root: context.projectRootPath, interactor: this.interactor, content})
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

        const searchProjectFile = ({text, path}: { text: string, path?: string }) => {
            return findFilesByName({text, path, root: context.projectRootPath, interactor: this.interactor})
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
                            type: "string",
                            description: "optional file path relative to the project root from which to start the search"
                        },
                    }
                },
                parse: JSON.parse,
                function: searchProjectFile
            }
        }

        const listProjectFilesAndDirectories = ({relPath}: { relPath: string }) => {
            return listFilesAndDirectories({relPath, root: context.projectRootPath, interactor: this.interactor})
        }

        const listFilesAndDirectoriesFunction: AssistantTool & RunnableToolFunction<{ relPath: string }> = {
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

        this.tools = [
            readProjectFileFunction,
            writeProjectFileFunction,
            searchProjectFileFunction,
            listFilesAndDirectoriesFunction
        ]
        return this.tools
    }

    private contextHasNotChanged(command: CommandContext): boolean {
        if (!this.lastToolInitContext) {
            this.lastToolInitContext = command
            return false
        }
        return !!this.lastToolInitContext &&
            command.projectRootPath === this.lastToolInitContext?.projectRootPath
    }
}


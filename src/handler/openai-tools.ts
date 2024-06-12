import {listFilesAndDirectories, readFileByPath, writeFile} from "../function"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {findFilesByName} from "../function/find-files-by-name"
import {Interactor} from "../interactor"
import {Beta} from "openai/resources"
import {AssistantToolFactory, Tool} from "./init-tools";
import AssistantTool = Beta.AssistantTool;
import {CommandContext} from "../command-context";

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
                            type: "string",
                            description: "optional file path relative to the project root from which to start the search"
                        }
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

        // TODO: not really working, LLM tries by itself to do all tasks defined before they are consumed from the queue
        // const subTask = ({subTasks}: { subTasks: {description: string}[] }) => {
        //     subTasks.forEach(
        //         subTask => this.interactor.displayText(`Sub-task queued: ${subTask.description}`)
        //     )
        //     context.addCommands(...subTasks.map(subTask => `ai ${subTask.description}`))
        //     return "sub-tasks received and queued for execution"
        // }
        //
        // const subTaskFunction: AssistantTool & RunnableToolFunction<{ subTasks: {description: string}[] }> = {
        //     type: "function",
        //     function: {
        //         name: "subTask",
        //         description: "Break down the current assignment into several simpler sub-tasks that will be run sequentially.",
        //         parameters: {
        //             type: "object",
        //             properties: {
        //                 subTasks: {
        //                     type: "array",
        //                     description: "Ordered list of sub-tasks",
        //                     items: {
        //                         type: "object",
        //                         properties: {
        //                             description: {
        //                                 type: "string",
        //                                 description: "Description of the sub-task. Take care to add a little bit of context but mainly focus on the task, describing the expectations on its completion."
        //                             }
        //                         }
        //                     }
        //                 },
        //             }
        //         },
        //         parse: JSON.parse,
        //         function: subTask
        //     }
        // }
        // result.push(subTaskFunction)

        return result

    }
}
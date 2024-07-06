import {AssistantToolFactory, Tool} from "../assistant-tool-factory"
import {Interactor} from "../../model/interactor"
import {CommandContext} from "../../model/command-context"
import {Beta} from "openai/resources"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {readFileByPath} from "./read-file-by-path"
import {writeFileByPath} from "./write-file-by-path"
import {findFilesByName} from "../../function/find-files-by-name"
import {listFilesAndDirectories} from "./list-files-and-directories"
import {findFilesByText} from "./find-files-by-text"
import AssistantTool = Beta.AssistantTool


export class FileTools extends AssistantToolFactory {
  
  constructor(interactor: Interactor) {
    super(interactor)
  }
  
  protected hasChanged(context: CommandContext): boolean {
    return true
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
      return writeFileByPath({relPath: path, root: context.project.root, interactor: this.interactor, content})
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
    
    const searchFilesByText = ({text, path, fileTypes}: { text: string, path?: string, fileTypes?: string[] }) => {
      return findFilesByText({
        text,
        path,
        root: context.project.root,
        interactor: this.interactor,
        fileTypes,
      })
    }
    
    const searchFilesByTextFunction: AssistantTool & RunnableToolFunction<{
      text: string,
      path?: string,
      fileTypes?: string[]
    }> = {
      type: "function",
      function: {
        name: "searchFilesByText",
        description: "search in the project for files containing the given text. The output is a list of paths relative to the project root. This function is slow, restrict scope by giving a path and fileTypes if possible, to avoid a timeout.",
        parameters: {
          type: "object",
          properties: {
            text: {type: "string", description: "text to search for inside files"},
            path: {
              type: "string",
              description: "optional file path relative to the project root from which to start the search"
            },
            fileTypes: {
              type: "array",
              items: {type: "string"},
              description: "optional but highly recommended array of file extensions to limit the search (e.g., ['js', 'ts'])"
            }
          }
        },
        parse: JSON.parse,
        function: searchFilesByText
      }
    }
    result.push(searchFilesByTextFunction)
    
    return result
  }
}

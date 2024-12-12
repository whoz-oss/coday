import { writeFileByPath } from './write-file-by-path'
import { writeFileChunk } from './write-file-chunk'
import { findFilesByName } from '../../function/find-files-by-name'
import { listFilesAndDirectories } from './list-files-and-directories'
import { findFilesByText } from './find-files-by-text'
import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { unlinkFile } from './unlink-file'
import { readFileByPath } from '../../function/read-file-by-path'

/**
 * FileTools: A comprehensive file manipulation tool factory for Coday
 * 
 * This class extends AssistantToolFactory and provides a robust set of file-related tools
 * that can be dynamically generated based on the current command context. It supports:
 * 
 * 1. Read Operations:
 *    - Reading project files
 *    - Searching files by name or content
 *    - Listing files and directories
 * 
 * 2. Write Operations (when not in read-only mode):
 *    - Writing entire files
 *    - Performing partial file edits (chunk replacements)
 *    - Removing files
 * 
 * Key Features:
 * - Respects read-only mode by conditionally adding write/delete tools
 * - Provides flexible file search capabilities
 * - Integrates with project's root directory
 * - Uses an interactor for logging and error handling
 * 
 * Design Principles:
 * - Dynamic tool generation based on context
 * - Clear, descriptive function tools with JSON parsing
 * - Error handling for file operations
 * 
 * @extends AssistantToolFactory
 */
export class FileTools extends AssistantToolFactory {
  constructor(interactor: Interactor) {
    super(interactor)
  }

  protected hasChanged(context: CommandContext): boolean {
    return true
  }

  protected buildTools(context: CommandContext): CodayTool[] {
    const result: CodayTool[] = []

    // Only add write/delete tools if not in read-only mode
    if (!context.fileReadOnly) {
      const removeFile = ({ path }: { path: string }) => {
        return unlinkFile(path, this.interactor)
      }

      const removeFileFunction: FunctionTool<{ path: string }> = {
        type: 'function',
        function: {
          name: 'removeFile',
          description: 'Remove the file at the given path in the project.',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'file path relative to the project root' },
            },
          },
          parse: JSON.parse,
          function: removeFile,
        },
      }
      result.push(removeFileFunction)
    }

    const readProjectFile = (input: { filePath: string }) => {
      return readFileByPath({ relPath: input.filePath, root: context.project.root, interactor: this.interactor })
    }

    const readProjectFileFunction: FunctionTool<{ filePath: string }> = {
      type: 'function',
      function: {
        name: 'readProjectFile',
        description: 'read the content of the file at the given path in the project.',
        parameters: {
          type: 'object',
          properties: {
            filePath: { type: 'string', description: 'file path relative to the project root' },
          },
        },
        parse: JSON.parse,
        function: readProjectFile,
      },
    }
    result.push(readProjectFileFunction)

    // Only add write tools if not in read-only mode
    if (!context.fileReadOnly) {
      const writeProjectFile = ({ path, content }: { path: string; content: string }) => {
        return writeFileByPath({ relPath: path, root: context.project.root, interactor: this.interactor, content })
      }

      const writeProjectFileFunction: FunctionTool<{ path: string; content: string }> = {
        type: 'function',
        function: {
          name: 'writeProjectFile',
          description:
            'write the content of the file at the given path in the project. IMPORTANT: the whole file is written, do not write it partially. Prefer this tool for first writes or really full edits. For partial edits, use `writeFileChunk` tool.',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'file path relative to the project root' },
              content: { type: 'string', description: 'content of the file to write' },
            },
          },
          parse: JSON.parse,
          function: writeProjectFile,
        },
      }
      result.push(writeProjectFileFunction)

      const writeFileChunkFunction = ({
        path,
        replacements,
      }: {
        path: string
        replacements: { oldPart: string; newPart: string }[]
      }) => {
        return writeFileChunk({ relPath: path, root: context.project.root, interactor: this.interactor, replacements })
      }

      const writeFileChunkTool: FunctionTool<{
        path: string
        replacements: { oldPart: string; newPart: string }[]
      }> = {
        type: 'function',
        function: {
          name: 'writeFileChunk',
          description:
            'Replace specified parts of an existing file with new parts. The function reads the entire file content, performs the replacements, and writes the modified content back to the file. Useful for handling large files efficiently.',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'File path relative to the project root' },
              replacements: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: {
                    oldPart: {
                      type: 'string',
                      description:
                        'The part of the file content to be replaced, it should be large enough to be unique.',
                    },
                    newPart: { type: 'string', description: 'The new content to replace all the old part.' },
                  },
                },
                description: 'Array of objects specifying the parts to replace and their respective replacements.',
              },
            },
          },
          parse: JSON.parse,
          function: writeFileChunkFunction,
        },
      }
      result.push(writeFileChunkTool)
    }

    const searchProjectFile = ({ text, path }: { text: string; path?: string }) => {
      return findFilesByName({ text, path, root: context.project.root, interactor: this.interactor, limit: 100 })
    }

    const searchProjectFileFunction: FunctionTool<{ text: string; path?: string }> = {
      type: 'function',
      function: {
        name: 'searchProjectFile',
        description:
          'search in the project for files entirely or partially named with the given text. The output is a list of paths relative to the project root. Prefer it over `searchFilesByText` when you are apparently dealing with a file name.',
        parameters: {
          type: 'object',
          properties: {
            text: { type: 'string', description: 'Part of the name, or full name of files to search for.' },
            path: {
              type: 'string',
              description: 'optional file path relative to the project root from which to start the search',
            },
          },
        },
        parse: JSON.parse,
        function: searchProjectFile,
      },
    }
    result.push(searchProjectFileFunction)

    const listProjectFilesAndDirectories = ({ relPath }: { relPath: string }) => {
      try {
        return listFilesAndDirectories({ relPath, root: context.project.root, interactor: this.interactor })
      } catch (error: any) {
        const errorMessage = `Could not list files and directories in '${relPath}', got error:\n${error}`
        this.interactor.error(errorMessage)
        return errorMessage
      }
    }

    const listProjectFilesAndDirectoriesFunction: FunctionTool<{ relPath: string }> = {
      type: 'function',
      function: {
        name: 'listFilesAndDirectories',
        description:
          'list the directories and files in a folder (similar to the ls Unix command). Directories end with a slash.',
        parameters: {
          type: 'object',
          properties: {
            relPath: { type: 'string', description: 'path relative to the project root' },
          },
        },
        parse: JSON.parse,
        function: listProjectFilesAndDirectories,
      },
    }
    result.push(listProjectFilesAndDirectoriesFunction)

    const searchFilesByText = ({ text, path, fileTypes }: { text: string; path?: string; fileTypes?: string[] }) => {
      return findFilesByText({
        text,
        path,
        root: context.project.root,
        interactor: this.interactor,
        fileTypes,
      })
    }

    const searchFilesByTextFunction: FunctionTool<{
      text: string
      path?: string
      fileTypes?: string[]
    }> = {
      type: 'function',
      function: {
        name: 'searchFilesByText',
        description:
          'Search in the project for files containing the given text in the content. The output is a list of paths relative to the project root. This function is slow, restrict scope by giving a path and fileTypes if possible, to avoid a timeout. If searching for a filename, prefer `searchProjectFile`.',
        parameters: {
          type: 'object',
          properties: {
            text: { type: 'string', description: 'text to search for inside files' },
            path: {
              type: 'string',
              description: 'optional file path relative to the project root from which to start the search',
            },
            fileTypes: {
              type: 'array',
              items: { type: 'string' },
              description:
                "optional but highly recommended array of file extensions to limit the search (e.g., ['js', 'ts'])",
            },
          },
        },
        parse: JSON.parse,
        function: searchFilesByText,
      },
    }
    result.push(searchFilesByTextFunction)

    return result
  }
}

import { writeFileByPath } from './write-file-by-path'
import { writeFileChunk } from './write-file-chunk'
import { findFilesByName } from '../../function/find-files-by-name'
import { listFilesAndDirectories } from './list-files-and-directories'
import { findFilesByText } from './find-files-by-text'
import { CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { readFileUnifiedAsMessageContent } from '../../function/read-file-unified'
import { resolveFilePath, prefixSearchResults } from './resolve-file-path'
import { FileEvent } from '@coday/coday-events'
import { unlinkSync } from 'node:fs'
import * as pathModule from 'path'

/**
 * FileTools: A comprehensive file manipulation tool factory for Coday
 *
 * This class extends AssistantToolFactory and provides a robust set of file-related tools
 * that can be dynamically generated based on the current command context. It supports:
 *
 * 1. Read Operations:
 *    - Unified file reading with automatic format detection
 *    - Searching files by name or content
 *    - Listing files and directories
 *
 * 2. Write Operations (when not in read-only mode):
 *    - Writing entire files
 *    - Performing partial file edits (chunk replacements)
 *    - Removing files
 *
 * File Path Prefixes:
 * - "project://" - Project source files (the codebase being worked on)
 * - "thread://" - Conversation workspace files (files uploaded by user or created during this specific conversation)
 *
 * The thread workspace is isolated per conversation and serves as a temporary exchange space
 * for documents, reports, and other files specific to the current conversation context.
 *
 * Key Features:
 * - Respects read-only mode by conditionally adding write/delete tools
 * - Provides flexible file search capabilities
 * - Integrates with project's root directory
 * - Uses an interactor for logging and error handling
 * - Unified file reading with automatic format detection
 *
 * Design Principles:
 * - Dynamic tool generation based on context
 * - Clear, descriptive function tools with JSON parsing
 * - Error handling for file operations
 * - Single responsibility per tool
 *
 * @extends AssistantToolFactory
 */
export class FileTools extends AssistantToolFactory {
  name = 'FILES'

  constructor(interactor: Interactor) {
    super(interactor)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    // Only add write/delete tools if not in read-only mode
    if (!context.fileReadOnly) {
      const removeFile = ({ path }: { path: string }) => {
        const resolved = resolveFilePath(path, context)

        // Check permissions for project files
        if (resolved.scope === 'project' && context.fileReadOnly) {
          throw new Error('Cannot delete project files in read-only mode')
        }

        try {
          unlinkSync(resolved.absolutePath)

          // Emit FileEvent for thread files
          if (resolved.scope === 'thread') {
            const event = new FileEvent({
              filename: resolved.relativePath,
              operation: 'deleted',
            })
            this.interactor.sendEvent(event)
          }

          return 'File deleted successfully'
        } catch (error) {
          this.interactor.error(`Error deleting file ${resolved.absolutePath}`)
          return `Error deleting file: ${error}`
        }
      }

      const removeFileFunction: FunctionTool<{ path: string }> = {
        type: 'function',
        function: {
          name: 'removeFile',
          description:
            'Remove a file. File path must start with "project://" (for project files) or "thread://" (for conversation files).',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'File path with prefix (e.g., "project://temp/old.txt" or "thread://draft.md")',
              },
            },
          },
          parse: JSON.parse,
          function: removeFile,
        },
      }
      result.push(removeFileFunction)
    }

    // Only add write tools if not in read-only mode
    if (!context.fileReadOnly) {
      const writeProjectFile = ({ path, content }: { path: string; content: string }) => {
        const resolved = resolveFilePath(path, context)

        // Check permissions for project files
        if (resolved.scope === 'project' && context.fileReadOnly) {
          throw new Error('Cannot write to project files in read-only mode')
        }

        // Use writeFileByPath with root as dirname and relPath as basename
        const result = writeFileByPath({
          relPath: pathModule.basename(resolved.absolutePath),
          root: pathModule.dirname(resolved.absolutePath),
          interactor: this.interactor,
          content,
        })

        // Emit FileEvent for thread files
        if (resolved.scope === 'thread') {
          const fileSize = Buffer.from(content).length
          const event = new FileEvent({
            filename: resolved.relativePath,
            operation: 'created', // Could be 'updated' if file exists, but we'll keep it simple
            size: fileSize,
          })
          this.interactor.sendEvent(event)
        }

        return result
      }

      const writeProjectFileFunction: FunctionTool<{ path: string; content: string }> = {
        type: 'function',
        function: {
          name: 'writeProjectFile',
          description:
            'Write the content of a file. IMPORTANT: the whole file is written, do not write it partially. ' +
            'Prefer this tool for first writes or really full edits. For partial edits, use `writeFileChunk` tool. ' +
            'File path must start with "project://" (for project files) or "thread://" (for conversation files).',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'File path with prefix (e.g., "project://output/report.md" or "thread://analysis.md")',
              },
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
        const resolved = resolveFilePath(path, context)

        // Check permissions for project files
        if (resolved.scope === 'project' && context.fileReadOnly) {
          throw new Error('Cannot write to project files in read-only mode')
        }

        const result = writeFileChunk({
          relPath: pathModule.basename(resolved.absolutePath),
          root: pathModule.dirname(resolved.absolutePath),
          interactor: this.interactor,
          replacements,
        })

        // Emit FileEvent for thread files
        if (resolved.scope === 'thread') {
          const event = new FileEvent({
            filename: resolved.relativePath,
            operation: 'updated',
          })
          this.interactor.sendEvent(event)
        }

        return result
      }

      const writeFileChunkTool: FunctionTool<{
        path: string
        replacements: { oldPart: string; newPart: string }[]
      }> = {
        type: 'function',
        function: {
          name: 'writeFileChunk',
          description:
            'Replace specified parts of an existing file with new parts. The function reads the entire file content, performs the replacements, and writes the modified content back to the file. ' +
            'Useful for handling large files efficiently. File path must start with "project://" or "thread://".',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'File path with prefix (e.g., "project://src/main.ts" or "thread://report.md")',
              },
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

    const searchProjectFile = async ({ text, path }: { text: string; path?: string }) => {
      const results: string[] = []

      // Search in thread files if available
      if (context.threadFilesRoot && (!path || path.startsWith('thread://'))) {
        const threadPath = path?.replace('thread://', '')
        const threadResults = await findFilesByName({
          text,
          path: threadPath,
          root: context.threadFilesRoot,
          limit: 50,
        })
        results.push(...prefixSearchResults(threadResults, 'thread'))
      }

      // Search in project (unless path explicitly starts with thread://)
      if (!path || !path.startsWith('thread://')) {
        const projectPath = path?.replace('project://', '')
        const projectResults = await findFilesByName({
          text,
          path: projectPath,
          root: context.project.root,
          limit: 50,
        })
        results.push(...prefixSearchResults(projectResults, 'project'))
      }

      return results.slice(0, 100) // Limit total results
    }

    const searchProjectFileFunction: FunctionTool<{ text: string; path?: string }> = {
      type: 'function',
      function: {
        name: 'searchProjectFile',
        description:
          'Search for files by name in both project and conversation files. Returns paths with "project://" or "thread://" prefix. ' +
          'Prefer this over `searchFilesByText` when searching by filename.',
        parameters: {
          type: 'object',
          properties: {
            text: { type: 'string', description: 'Part of the name, or full name of files to search for.' },
            path: {
              type: 'string',
              description:
                'Optional path to start search from. Can use "project://" or "thread://" prefix to search only in that space.',
            },
          },
        },
        parse: JSON.parse,
        function: searchProjectFile,
      },
    }
    result.push(searchProjectFileFunction)

    const listProjectFilesAndDirectories = ({ relPath }: { relPath: string }) => {
      // Require explicit prefix for root-level listing
      if (!relPath || relPath === '.' || relPath === '/') {
        throw new Error(
          'Path must start with "project://" or "thread://" prefix. ' +
            'Use "project://" to list project files or "thread://" to list conversation files.'
        )
      }

      const resolved = resolveFilePath(relPath, context)

      // For listing the root of a space (thread:// or project://), use the resolved path directly
      // Otherwise, use the parent directory
      if (!resolved.relativePath || resolved.relativePath === '' || resolved.relativePath === '.') {
        // Listing the root of the space
        return listFilesAndDirectories({ relPath: '.', root: resolved.absolutePath })
      } else {
        // Listing a subdirectory
        return listFilesAndDirectories({
          relPath: resolved.relativePath,
          root: pathModule.dirname(resolved.absolutePath),
        })
      }
    }

    const listProjectFilesAndDirectoriesFunction: FunctionTool<{ relPath: string }> = {
      type: 'function',
      function: {
        name: 'listFilesAndDirectories',
        description:
          'List directories and files in a folder (similar to ls command). Directories end with a slash. ' +
          'Path must start with "project://" or "thread://" prefix.',
        parameters: {
          type: 'object',
          properties: {
            relPath: {
              type: 'string',
              description: 'Path with prefix (e.g., "project://src" or "thread://")',
            },
          },
        },
        parse: JSON.parse,
        function: listProjectFilesAndDirectories,
      },
    }
    result.push(listProjectFilesAndDirectoriesFunction)

    const searchFilesByText = async ({
      text,
      path,
      fileTypes,
    }: {
      text: string
      path?: string
      fileTypes?: string[]
    }) => {
      const resultLines: string[] = []

      // Search in thread files if available
      if (context.threadFilesRoot && (!path || path.startsWith('thread://'))) {
        const threadPath = path?.replace('thread://', '')
        const threadResultsRaw = await findFilesByText({
          text,
          path: threadPath,
          root: context.threadFilesRoot,
          interactor: this.interactor,
          fileTypes,
        })

        if (threadResultsRaw && threadResultsRaw !== 'No match found') {
          const threadFiles = threadResultsRaw
            .trim()
            .split('\n')
            .filter((f) => f)
          resultLines.push(...prefixSearchResults(threadFiles, 'thread'))
        }
      }

      // Search in project (unless path explicitly starts with thread://)
      if (!path || !path.startsWith('thread://')) {
        const projectPath = path?.replace('project://', '')
        const projectResultsRaw = await findFilesByText({
          text,
          path: projectPath,
          root: context.project.root,
          interactor: this.interactor,
          fileTypes,
        })

        if (projectResultsRaw && projectResultsRaw !== 'No match found') {
          const projectFiles = projectResultsRaw
            .trim()
            .split('\n')
            .filter((f) => f)
          resultLines.push(...prefixSearchResults(projectFiles, 'project'))
        }
      }

      return resultLines.length > 0 ? resultLines.join('\n') : 'No match found'
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
          'Search for files containing text in both project and conversation files. Returns paths with "project://" or "thread://" prefix. ' +
          'This function is slow, restrict scope by giving a path and fileTypes if possible, to avoid a timeout. If searching for a filename, prefer `searchProjectFile`.',
        parameters: {
          type: 'object',
          properties: {
            text: { type: 'string', description: 'text to search for inside files' },
            path: {
              type: 'string',
              description:
                'Optional path to start search from. Can use "project://" or "thread://" prefix to search only in that space.',
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

    // Unified file reader tool - handles all file types automatically including images
    const readFileUnified = async ({ filePath }: { filePath: string }) => {
      const resolved = resolveFilePath(filePath, context)

      return readFileUnifiedAsMessageContent({
        absolutePath: resolved.absolutePath,
        interactor: this.interactor,
      })
    }

    const readFileFunction: FunctionTool<{ filePath: string }> = {
      type: 'function',
      function: {
        name: 'readFile',
        description:
          'Read content from any file type. Supports text files, PDFs, and image files (PNG, JPEG, GIF, WebP). ' +
          'File path must start with "project://" (for project files) or "thread://" (for conversation files). ' +
          'Use searchProjectFile or searchFilesByText to find files across both spaces.',
        parameters: {
          type: 'object',
          properties: {
            filePath: {
              type: 'string',
              description: 'File path with prefix (e.g., "project://src/main.ts" or "thread://document.pdf")',
            },
          },
        },
        parse: JSON.parse,
        function: readFileUnified,
      },
    }
    result.push(readFileFunction)

    return result
  }
}

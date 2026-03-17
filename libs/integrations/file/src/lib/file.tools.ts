import { writeFileByPath } from './write-file-by-path'
import { writeFileChunk } from './write-file-chunk'
import { listFilesAndDirectories } from './list-files-and-directories'
import { readFileUnifiedAsMessageContent } from '@coday/function'
import { resolveFilePath, FILE_PREFIXES } from './resolve-file-path'
import { FileEvent } from '@coday/model'
import { existsSync } from 'node:fs'
import { unlinkSync } from 'node:fs'
import * as pathModule from 'path'
import { AssistantToolFactory } from '@coday/model'
import { Interactor } from '@coday/model'
import { CommandContext } from '@coday/model'
import { CodayTool } from '@coday/model'
import { FunctionTool } from '@coday/model'
import { searchFiles, buildSearchResult } from './search-files'
import { moveFile } from './move-file'
import { IntegrationConfig } from '@coday/model'

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
 * - "exchange://" - Conversation workspace files (files uploaded by user or created during this specific conversation)
 *
 * The exchange workspace is isolated per conversation and serves as a temporary exchange space
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
  static readonly TYPE = 'FILES' as const

  constructor(interactor: Interactor, instanceName: string, config: IntegrationConfig) {
    super(interactor, instanceName, config)
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

          // Emit FileEvent for exchange files
          if (resolved.scope === 'exchange') {
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
          name: `${this.name}__remove`,
          description:
            'Remove a file. File path must start with "project://" (for project files) or "exchange://" (for files shared with the user).',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'File path with prefix (e.g., "project://temp/old.txt" or "exchange://draft.md")',
              },
            },
          },
          parse: JSON.parse,
          function: removeFile,
        },
      }
      result.push(removeFileFunction)
    }

    const listProjectFilesAndDirectories = ({ relPath }: { relPath: string }) => {
      // Require explicit prefix for root-level listing
      if (!relPath || relPath === '.' || relPath === '/') {
        throw new Error(
          'Path must start with "project://" or "exchange://" prefix. ' +
            'Use "project://" to list project files or "exchange://" to list files shared with the users.'
        )
      }

      const resolved = resolveFilePath(relPath, context)

      // Use the resolved absolute path directly
      // The resolved.absolutePath already contains the full path to the directory to list
      // We pass '.' as relPath to listFilesAndDirectories to list the contents of that directory
      return listFilesAndDirectories({ relPath: '.', root: resolved.absolutePath })
    }

    const listProjectFilesAndDirectoriesFunction: FunctionTool<{ relPath: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__ls`,
        description:
          'List directories and files in a folder (similar to ls command). Directories end with a slash. ' +
          'Path must start with "project://" or "exchange://" prefix.',
        parameters: {
          type: 'object',
          properties: {
            relPath: {
              type: 'string',
              description: 'Path with prefix (e.g., "project://src" or "exchange://")',
            },
          },
        },
        parse: JSON.parse,
        function: listProjectFilesAndDirectories,
      },
    }
    result.push(listProjectFilesAndDirectoriesFunction)

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
        name: `${this.name}__readFile`,
        description:
          'Read content from any file type. Supports text files, PDFs, and image files (PNG, JPEG, GIF, WebP). ' +
          'File path must start with "project://" (for project files) or "exchange://" (for files shared with the user). ' +
          'Use searchFiles to find files across both spaces.',
        parameters: {
          type: 'object',
          properties: {
            filePath: {
              type: 'string',
              description: 'File path with prefix (e.g., "project://src/main.ts" or "exchange://document.pdf")',
            },
          },
        },
        parse: JSON.parse,
        function: readFileUnified,
      },
    }
    result.push(readFileFunction)

    // New tools (#391)
    result.push(this.buildSearchFilesTool(context))
    if (!context.fileReadOnly) {
      result.push(this.buildEditFilesTool(context))
      result.push(this.buildMoveFileTool(context))
    }

    return result
  }

  // -------------------------------------------------------------------------
  // NEW TOOLS (#391)
  // -------------------------------------------------------------------------

  private buildSearchFilesTool(context: CommandContext): FunctionTool<{
    fileName?: string
    fileContent?: string
    path?: string
    fileTypes?: string[]
  }> {
    const searchFilesHandler = async ({
      fileName,
      fileContent,
      path,
      fileTypes,
    }: {
      fileName?: string
      fileContent?: string
      path?: string
      fileTypes?: string[]
    }) => {
      if (!fileName && !fileContent) {
        return 'At least one of fileName or fileContent must be provided.'
      }

      const results: string[] = []

      // Search in exchange workspace (only if the directory actually exists on disk)
      if (
        context.threadFilesRoot &&
        existsSync(context.threadFilesRoot) &&
        (!path || path.startsWith(FILE_PREFIXES.EXCHANGE))
      ) {
        const exchangePath = path?.replace(FILE_PREFIXES.EXCHANGE, '')
        const { files } = await searchFiles({
          fileName,
          fileContent,
          searchPath: exchangePath,
          root: context.threadFilesRoot,
          fileTypes,
          interactor: this.interactor,
        })
        const result = buildSearchResult({ files, root: context.threadFilesRoot, prefix: FILE_PREFIXES.EXCHANGE })
        results.push(result)
      }

      // Search in project
      if (!path?.startsWith(FILE_PREFIXES.EXCHANGE)) {
        const projectPath = path?.replace(FILE_PREFIXES.PROJECT, '')
        const { files } = await searchFiles({
          fileName,
          fileContent,
          searchPath: projectPath,
          root: context.project.root,
          fileTypes,
          interactor: this.interactor,
        })
        const result = buildSearchResult({ files, root: context.project.root, prefix: FILE_PREFIXES.PROJECT })
        results.push(result)
      }

      const combined = results.filter((r) => r !== 'No matching files found.').join('\n\n')
      return combined || 'No matching files found.'
    }

    return {
      type: 'function',
      function: {
        name: `${this.name}__searchFiles`,
        description:
          'Search for files by name pattern and/or content text. ' +
          'At least one of fileName or fileContent must be provided. ' +
          'When both are provided, only files whose name matches fileName AND whose content matches fileContent are returned. ' +
          'If the total size of matching files is reasonable, their content is returned directly. ' +
          'Otherwise, only the list of matching paths is returned. ' +
          'Paths are prefixed with "project://" or "exchange://".',
        parameters: {
          type: 'object',
          properties: {
            fileName: {
              type: 'string',
              description: 'Partial file name or pattern to match against file names (e.g. "config", "user.service").',
            },
            fileContent: {
              type: 'string',
              description: 'Text to search for inside file contents.',
            },
            path: {
              type: 'string',
              description: 'Optional path to restrict the search scope. Use "project://" or "exchange://" prefix.',
            },
            fileTypes: {
              type: 'array',
              items: { type: 'string' },
              description: 'Optional array of file extensions to restrict content search (e.g. ["ts", "json"]).',
            },
          },
        },
        parse: JSON.parse,
        function: searchFilesHandler,
      },
    }
  }

  private buildEditFilesTool(context: CommandContext): FunctionTool<{
    edits: Array<
      | { operation: 'write'; path: string; content: string }
      | { operation: 'patch'; path: string; replacements: { oldPart: string; newPart: string }[] }
    >
  }> {
    const editFilesHandler = ({
      edits,
    }: {
      edits: Array<
        | { operation: 'write'; path: string; content: string }
        | { operation: 'patch'; path: string; replacements: { oldPart: string; newPart: string }[] }
      >
    }) => {
      if (!edits || !Array.isArray(edits) || edits.length === 0) {
        return 'No edits provided.'
      }

      const results: string[] = []

      for (const edit of edits) {
        try {
          const resolved = resolveFilePath(edit.path, context)

          if (edit.operation === 'write') {
            const outcome = writeFileByPath({
              relPath: pathModule.basename(resolved.absolutePath),
              root: pathModule.dirname(resolved.absolutePath),
              interactor: this.interactor,
              content: edit.content,
            })
            if (resolved.scope === 'exchange') {
              const event = new FileEvent({
                filename: resolved.relativePath,
                operation: 'created',
                size: Buffer.from(edit.content).length,
              })
              this.interactor.sendEvent(event)
            }
            results.push(`${edit.path}: ${outcome}`)
          } else if (edit.operation === 'patch') {
            const outcome = writeFileChunk({
              relPath: pathModule.basename(resolved.absolutePath),
              root: pathModule.dirname(resolved.absolutePath),
              interactor: this.interactor,
              replacements: edit.replacements,
            })
            if (resolved.scope === 'exchange') {
              const event = new FileEvent({
                filename: resolved.relativePath,
                operation: 'updated',
              })
              this.interactor.sendEvent(event)
            }
            results.push(`${edit.path}: ${outcome}`)
          } else {
            results.push(`${(edit as { path: string }).path}: Unknown operation`)
          }
        } catch (error) {
          results.push(`${edit.path}: Error — ${error}`)
        }
      }

      return results.join('\n')
    }

    return {
      type: 'function',
      function: {
        name: 'editFiles',
        description:
          'Edit one or more files in a single tool call. ' +
          'Each edit targets a specific file and specifies an operation: ' +
          '"write" replaces the entire file content (or creates it), ' +
          '"patch" replaces specific chunks within an existing file. ' +
          'Edits are executed independently: a failure on one file does not prevent others from being processed. ' +
          'File paths must start with "project://" or "exchange://".',
        parameters: {
          type: 'object',
          properties: {
            edits: {
              type: 'array',
              description: 'List of file edits to perform.',
              items: {
                type: 'object',
                properties: {
                  operation: {
                    type: 'string',
                    enum: ['write', 'patch'],
                    description: '"write" to overwrite/create the file, "patch" to replace specific chunks.',
                  },
                  path: {
                    type: 'string',
                    description: 'File path with prefix (e.g. "project://src/main.ts" or "exchange://report.md").',
                  },
                  content: {
                    type: 'string',
                    description: 'Full file content. Required for "write" operation.',
                  },
                  replacements: {
                    type: 'array',
                    description: 'Required for "patch" operation.',
                    items: {
                      type: 'object',
                      properties: {
                        oldPart: {
                          type: 'string',
                          description: 'Existing content to replace (must be unique and at least 15 chars).',
                        },
                        newPart: {
                          type: 'string',
                          description: 'Replacement content.',
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        },
        parse: JSON.parse,
        function: editFilesHandler,
      },
    }
  }

  private buildMoveFileTool(context: CommandContext): FunctionTool<{ from: string; to: string }> {
    const moveFileHandler = ({ from, to }: { from: string; to: string }) => {
      const resolvedFrom = resolveFilePath(from, context)
      const resolvedTo = resolveFilePath(to, context)

      if (resolvedFrom.scope !== resolvedTo.scope) {
        return `Cannot move files between scopes: "${from}" (${resolvedFrom.scope}) → "${to}" (${resolvedTo.scope}). Use separate copy and delete operations.`
      }

      const { success, message } = moveFile({
        fromAbsolute: resolvedFrom.absolutePath,
        toAbsolute: resolvedTo.absolutePath,
      })

      if (success && resolvedFrom.scope === 'exchange') {
        this.interactor.sendEvent(new FileEvent({ filename: resolvedFrom.relativePath, operation: 'deleted' }))
        this.interactor.sendEvent(new FileEvent({ filename: resolvedTo.relativePath, operation: 'created' }))
      }

      return message
    }

    return {
      type: 'function',
      function: {
        name: `${this.name}__moveFile`,
        description:
          'Move or rename a file within the same scope (project or exchange). ' +
          'Fails if the source does not exist or the destination already exists. ' +
          'Cross-scope moves (project ↔ exchange) are not allowed. ' +
          'File paths must start with "project://" or "exchange://".',
        parameters: {
          type: 'object',
          properties: {
            from: {
              type: 'string',
              description: 'Source file path with prefix (e.g. "project://old/path.ts").',
            },
            to: {
              type: 'string',
              description: 'Destination file path with prefix (e.g. "project://new/path.ts").',
            },
          },
        },
        parse: JSON.parse,
        function: moveFileHandler,
      },
    }
  }
}

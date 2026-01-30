import * as path from 'path'
import { CommandContext } from '@coday/model'

/**
 * File path prefixes for different file scopes
 */
export const FILE_PREFIXES = {
  PROJECT: 'project://',
  EXCHANGE: 'exchange://',
} as const

export type FileScope = 'project' | 'exchange'

export interface ResolvedPath {
  absolutePath: string
  scope: FileScope
  relativePath: string
}

/**
 * Resolve a file path with prefix (project:// or exchange://) to an absolute path
 *
 * @param filePath Path with prefix (project://... or exchange://...)
 * @param context Command context containing project root and thread files root
 * @returns Resolved path information
 * @throws Error if path is invalid or exchange files not available
 */
export function resolveFilePath(filePath: string, context: CommandContext): ResolvedPath {
  if (filePath.startsWith(FILE_PREFIXES.PROJECT)) {
    const relativePath = filePath.slice(FILE_PREFIXES.PROJECT.length)
    validatePathTraversal(relativePath)

    return {
      absolutePath: path.join(context.project.root, relativePath),
      scope: 'project',
      relativePath,
    }
  }

  if (filePath.startsWith(FILE_PREFIXES.EXCHANGE)) {
    if (!context.threadFilesRoot) {
      throw new Error('Exchange workspace not available in this context')
    }

    const relativePath = filePath.slice(FILE_PREFIXES.EXCHANGE.length)
    validatePathTraversal(relativePath)

    return {
      absolutePath: path.join(context.threadFilesRoot, relativePath),
      scope: 'exchange',
      relativePath,
    }
  }

  throw new Error(
    `File path must start with "${FILE_PREFIXES.PROJECT}" or "${FILE_PREFIXES.EXCHANGE}". ` +
      'Use searchProjectFile or searchFilesByText to find files.'
  )
}

/**
 * Validate that a path doesn't contain traversal attempts (../)
 *
 * @param relativePath Relative path to validate
 * @throws Error if path contains traversal attempts
 */
function validatePathTraversal(relativePath: string): void {
  // Normalize the path to resolve any .. or . segments
  const normalized = path.normalize(relativePath)

  // Check if the normalized path tries to go outside (starts with ..)
  if (normalized.startsWith('..') || normalized.includes(path.sep + '..')) {
    throw new Error(`Invalid path: path traversal not allowed (${relativePath})`)
  }
}

/**
 * Add prefix to search results based on scope
 * Returns results with appropriate prefixes
 *
 * @param results Array of relative paths from search
 * @param scope Which scope the results came from
 * @returns Array of paths with appropriate prefix
 */
export function prefixSearchResults(results: string[], scope: FileScope): string[] {
  const prefix = scope === 'project' ? FILE_PREFIXES.PROJECT : FILE_PREFIXES.EXCHANGE
  return results.map((result) => `${prefix}${result}`)
}

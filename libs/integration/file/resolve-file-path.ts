import * as path from 'path'
import { CommandContext } from '../../model'

export interface ResolvedPath {
  absolutePath: string
  scope: 'project' | 'thread'
  relativePath: string
}

/**
 * Resolve a file path with prefix (project:// or thread://) to an absolute path
 *
 * @param filePath Path with prefix (project://... or thread://...)
 * @param context Command context containing project root and thread files root
 * @returns Resolved path information
 * @throws Error if path is invalid or thread files not available
 */
export function resolveFilePath(filePath: string, context: CommandContext): ResolvedPath {
  if (filePath.startsWith('project://')) {
    const relativePath = filePath.slice(10) // Remove 'project://'
    validatePathTraversal(relativePath)

    return {
      absolutePath: path.join(context.project.root, relativePath),
      scope: 'project',
      relativePath,
    }
  }

  if (filePath.startsWith('thread://')) {
    if (!context.threadFilesRoot) {
      throw new Error('Thread files not available in this context')
    }

    const relativePath = filePath.slice(9) // Remove 'thread://'
    validatePathTraversal(relativePath)

    return {
      absolutePath: path.join(context.threadFilesRoot, relativePath),
      scope: 'thread',
      relativePath,
    }
  }

  throw new Error(
    'File path must start with "project://" or "thread://". ' +
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
 * Search for a file in both thread and project spaces
 * Returns results with appropriate prefixes
 *
 * @param results Array of relative paths from search
 * @param scope Which scope the results came from
 * @returns Array of paths with appropriate prefix
 */
export function prefixSearchResults(results: string[], scope: 'project' | 'thread'): string[] {
  const prefix = scope === 'project' ? 'project://' : 'thread://'
  return results.map((result) => `${prefix}${result}`)
}

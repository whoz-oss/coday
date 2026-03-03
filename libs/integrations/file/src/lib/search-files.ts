import { execFile } from 'child_process'
import { promisify } from 'util'
import { readFileSync } from 'node:fs'
import { Interactor } from '@coday/model'
import * as path from 'path'

const execFileAsync = promisify(execFile)

const defaultTimeout = 10000
const defaultMaxBuffer = 10 * 1024 * 1024 // 10MB

type SearchFilesResult = {
  files: string[] // relative paths from root
}

const runRg = (args: string[], root: string, timeout: number, interactor: Interactor): Promise<SearchFilesResult> =>
  execFileAsync('rg', args, { cwd: root, maxBuffer: defaultMaxBuffer, timeout })
    .then(({ stdout }) => ({
      files: stdout
        .trim()
        .split('\n')
        .filter((f) => f.length > 0),
    }))
    .catch((err: any) => {
      if (err.code === 1) return { files: [] }
      interactor.error(`searchFiles ripgrep error: ${err.stderr}`)
      throw new Error(`Search error: ${err.stderr}`)
    })

type SearchFilesInput = {
  fileName?: string
  fileContent?: string
  searchPath?: string
  root: string
  fileTypes?: string[]
  interactor: Interactor
  timeout?: number
}

/**
 * Search files by name (glob), content (ripgrep), or both combined.
 * When both criteria are provided, uses ripgrep with a glob pattern to combine them in a single pass.
 */
export const searchFiles = async ({
  fileName,
  fileContent,
  searchPath,
  root,
  fileTypes,
  interactor,
  timeout = defaultTimeout,
}: SearchFilesInput): Promise<SearchFilesResult> => {
  const resolvedSearchPath = searchPath ?? '.'

  if (fileContent) {
    // ripgrep handles content search, optionally filtered by fileName glob pattern.
    // Use execFile (not exec) to bypass shell interpretation of glob characters.
    const args = [fileContent, resolvedSearchPath, '--color', 'never', '-l', '--fixed-strings']
    if (fileName) args.push('--glob', `*${fileName}*`)
    for (const t of fileTypes ?? []) args.push('--glob', `*.${t}`)
    return runRg(args, root, timeout, interactor)
  }

  // fileName only: use ripgrep --files with glob pattern (faster and timeout-reliable)
  const args = ['--files', resolvedSearchPath, '--color', 'never', '--glob', `*${fileName}*`]
  for (const t of fileTypes ?? []) args.push('--glob', `*.${t}`)
  return runRg(args, root, timeout, interactor)
}

/**
 * Read text content of a file, returning null if the file is binary or unreadable as text.
 */
export const readFileAsText = (absolutePath: string): string | null => {
  try {
    return readFileSync(absolutePath, { encoding: 'utf8' })
  } catch {
    return null
  }
}

/**
 * Build a combined search result string for the agent.
 * If total content size is under the threshold, returns file contents.
 * Otherwise returns only the list of matching paths (prefixed).
 */
export const buildSearchResult = ({
  files,
  root,
  prefix,
  contentSizeThreshold = 200_000,
}: {
  files: string[]
  root: string
  prefix: string
  contentSizeThreshold?: number
}): string => {
  if (files.length === 0) {
    return 'No matching files found.'
  }

  // Estimate total content size before reading everything
  let totalSize = 0
  const contents: { relPath: string; text: string | null }[] = []

  for (const relPath of files) {
    const absolutePath = path.join(root, relPath)
    const text = readFileAsText(absolutePath)
    const size = text ? text.length : 0
    totalSize += size
    contents.push({ relPath, text })

    if (totalSize > contentSizeThreshold) {
      // Already over threshold, no point reading more
      break
    }
  }

  if (totalSize > contentSizeThreshold || contents.length < files.length) {
    // Return list only
    return files.map((f) => `${prefix}${f}`).join('\n')
  }

  // Return full contents
  return contents
    .map(({ relPath, text }) => {
      const header = `=== ${prefix}${relPath} ===`
      return text !== null ? `${header}\n${text}` : `${header}\n[binary or unreadable]`
    })
    .join('\n\n')
}

import { execFile } from 'child_process'
import { promisify } from 'util'
import { Interactor } from '@coday/model'
import { glob } from 'glob'
import * as path from 'path'

const execFileAsync = promisify(execFile)

const defaultTimeout = 10000
const defaultMaxBuffer = 10 * 1024 * 1024 // 10MB

type SearchFilesInput = {
  fileName?: string
  fileContent?: string
  searchPath?: string
  root: string
  fileTypes?: string[]
  interactor: Interactor
  timeout?: number
}

type SearchFilesResult = {
  files: string[] // relative paths from root
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

    if (fileTypes && fileTypes.length > 0) {
      for (const t of fileTypes) {
        args.push('--glob', `*.${t}`)
      }
    }

    // fileName glob restricts which files ripgrep searches — applied to basename only
    if (fileName) {
      args.push('--glob', `*${fileName}*`)
    }

    interactor.debug(`searchFiles ripgrep args: rg ${args.join(' ')}`)

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`Search timed out after ${timeout}ms`)), timeout)

      execFileAsync('rg', args, { cwd: root, maxBuffer: defaultMaxBuffer })
        .then(({ stdout }) => {
          clearTimeout(timer)
          const files = stdout
            .trim()
            .split('\n')
            .filter((f) => f.length > 0)
          resolve({ files })
        })
        .catch((err: any) => {
          clearTimeout(timer)
          if (err.code === 1) {
            // ripgrep exit code 1 = no matches
            resolve({ files: [] })
          } else {
            interactor.error(`searchFiles ripgrep error: ${err.stderr}`)
            reject(new Error(`Search error: ${err.stderr}`))
          }
        })
    })
  }

  // fileName only: use glob
  const fileTypePattern = fileTypes && fileTypes.length > 0 ? `*.{${fileTypes.join(',')}}` : null

  const expression = fileTypePattern
    ? `${resolvedSearchPath !== '.' ? resolvedSearchPath + '/' : ''}**/*${fileName}*.${fileTypePattern}`
    : `${resolvedSearchPath !== '.' ? resolvedSearchPath + '/' : ''}**/*${fileName}*`

  const results = await glob(expression, {
    cwd: root,
    absolute: false,
    dotRelative: false,
    follow: false,
    signal: AbortSignal.timeout(timeout),
    ignore: ['**/node_modules/**', '**/build/**', '**/dist/**'],
  })

  // Filter to files only (exclude directories) — glob may return dirs if pattern matches
  const files = results.filter((r) => !r.endsWith('/'))

  return { files }
}

/**
 * Read text content of a file, returning null if the file is binary or unreadable as text.
 */
export const readFileAsText = async (absolutePath: string): Promise<string | null> => {
  try {
    const { readFileSync } = await import('node:fs')
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
export const buildSearchResult = async ({
  files,
  root,
  prefix,
  contentSizeThreshold = 50_000,
}: {
  files: string[]
  root: string
  prefix: string
  contentSizeThreshold?: number
}): Promise<string> => {
  if (files.length === 0) {
    return 'No matching files found.'
  }

  // Estimate total content size before reading everything
  let totalSize = 0
  const contents: { relPath: string; text: string | null }[] = []

  for (const relPath of files) {
    const absolutePath = path.join(root, relPath)
    const text = await readFileAsText(absolutePath)
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

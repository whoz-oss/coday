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

/**
 * Detect the ripgrep binary name based on platform.
 * On Windows, 'rg' may not be found by execFile without shell; try 'rg.exe' as fallback.
 */
const getRgBinary = (): string => (process.platform === 'win32' ? 'rg.exe' : 'rg')

const runRg = (args: string[], root: string, timeout: number, interactor: Interactor): Promise<SearchFilesResult> => {
  const rgBinary = getRgBinary()
  interactor.debug(`searchFiles: running '${rgBinary} ${args.join(' ')}' in '${root}'`)
  return execFileAsync(rgBinary, args, {
    cwd: root,
    maxBuffer: defaultMaxBuffer,
    timeout,
    // On Windows, execFile without shell may fail to resolve binaries installed via PATH.
    // Using shell:true on Windows lets the OS find rg/rg.exe through standard PATH resolution.
    shell: process.platform === 'win32',
  })
    .then(({ stdout }) => ({
      files: stdout
        .trim()
        .split('\n')
        .filter((f) => f.length > 0)
        // Normalize path separators: ripgrep may return backslashes on Windows
        .map((f) => f.replace(/\\/g, '/')),
    }))
    .catch((err: any) => {
      // Exit code 1 from ripgrep means "no matches found" — not an error
      if (err.code === 1) {
        interactor.debug(`searchFiles: no matches found (ripgrep exit code 1)`)
        return { files: [] }
      }
      // ripgrep not found in PATH
      if (err.code === 'ENOENT') {
        interactor.error(
          `searchFiles: '${rgBinary}' not found. Please install ripgrep (https://github.com/BurntSushi/ripgrep#installation) and ensure it is in your PATH.`
        )
        throw new Error(`ripgrep ('${rgBinary}') not found in PATH`)
      }
      // Timeout
      if (err.killed || err.signal === 'SIGTERM') {
        interactor.error(`searchFiles: ripgrep timed out after ${timeout}ms`)
        throw new Error(`Search timed out after ${timeout}ms`)
      }
      // Generic error — include both stderr and message for diagnostics
      const detail = err.stderr || err.message || String(err)
      interactor.error(`searchFiles ripgrep error (code=${err.code}): ${detail}`)
      throw new Error(`Search error: ${detail}`)
    })
}

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
  const resolvedSearchPath = searchPath || '.'

  if (fileContent) {
    // ripgrep handles content search, optionally filtered by fileName glob pattern.
    // Use execFile (not exec) to bypass shell interpretation of glob characters.
    const args = [fileContent, resolvedSearchPath, '--color', 'never', '-l', '--fixed-strings']
    if (fileName) args.push('--glob', `*${fileName}*`)
    // fileTypes: use a single brace-expansion glob for AND semantics with fileName
    if (fileTypes && fileTypes.length > 0) {
      const extPattern = fileTypes.length === 1 ? `*.${fileTypes[0]}` : `*.{${fileTypes.join(',')}}`
      args.push('--glob', extPattern)
    }
    const result = await runRg(args, root, timeout, interactor)
    // Post-filter by extension for AND semantics (ripgrep treats multiple --glob as OR)
    if (fileTypes && fileTypes.length > 0) {
      const extensions = new Set(fileTypes.map((t) => `.${t}`))
      return { files: result.files.filter((f) => extensions.has(path.extname(f))) }
    }
    return result
  }

  // fileName only: use ripgrep --files with glob pattern (faster and timeout-reliable)
  const args = ['--files', resolvedSearchPath, '--color', 'never', '--glob', `*${fileName}*`]
  const result = await runRg(args, root, timeout, interactor)
  // If fileTypes are specified, filter results to only include matching extensions (AND logic)
  if (fileTypes && fileTypes.length > 0) {
    const extensions = new Set(fileTypes.map((t) => `.${t}`))
    return { files: result.files.filter((f) => extensions.has(path.extname(f))) }
  }
  return result
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

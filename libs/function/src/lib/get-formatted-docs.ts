import { WithDocs } from '@coday/model'
import * as path from 'node:path'
import { Interactor } from '@coday/model'
import * as fs from 'node:fs/promises'
import { FileContent } from './file-content'
import { readFileUnified } from './read-file-unified'

export async function getFormattedDocs(
  withDocs: WithDocs,
  interactor: Interactor,
  projectPath: string,
  contextName: string
): Promise<string> {
  let formattedDocs = ''
  let mandatoryDocText = ''
  let warnings = ''

  try {
    // Resolve each mandatoryDocs entry into one or more {docPath, content} results
    type DocResult = { content: string | null; error: string | null }

    const pathExists = (p: string): Promise<boolean> =>
      fs.access(p).then(
        () => true,
        () => false
      )

    const resolveEntry = async (entry: string): Promise<DocResult[]> => {
      // Format 1: ends with '/' → directory listing (first level only)
      if (entry.endsWith('/')) {
        const dirPath = path.resolve(projectPath, entry)
        if (!(await pathExists(dirPath))) {
          return [{ content: null, error: `Mandatory directory not found: ${entry}` }]
        }
        try {
          const items = (await fs.readdir(dirPath)).sort()
          const stats = await Promise.all(items.map((item) => fs.stat(path.join(dirPath, item))))
          const lines = items.map((item, i) => `  - ${item}${stats[i]?.isDirectory() ? '/' : ''}`)
          const listing = `Contents of ${entry}:\n${lines.join('\n')}`
          return [{ content: listing, error: null }]
        } catch (e: any) {
          return [{ content: null, error: `Could not list directory ${entry}: ${e.message}` }]
        }
      }

      // Format 2: ends with '/*' → read all files at path (non-recursive)
      if (entry.endsWith('/*')) {
        const dirEntry = entry.slice(0, -2) // strip trailing '/*'
        const dirPath = path.resolve(projectPath, dirEntry)
        if (!(await pathExists(dirPath))) {
          return [{ content: null, error: `Mandatory directory not found: ${entry}` }]
        }
        try {
          const items = (await fs.readdir(dirPath)).sort()
          const stats = await Promise.all(items.map((item) => fs.stat(path.join(dirPath, item))))
          const fileItems = items.filter((_, i) => stats[i]?.isFile())
          if (fileItems.length === 0) {
            return [{ content: null, error: `No files found in: ${entry}` }]
          }
          const results = await Promise.allSettled(
            fileItems.map((item) =>
              readFileUnified({
                relPath: path.join(dirEntry, item),
                root: projectPath,
                interactor,
              })
            )
          )
          return results.map((settled, i) => {
            const filePath = `${dirEntry}/${fileItems[i]}`
            if (settled.status === 'fulfilled') {
              const value = settled.value as FileContent
              if (value.type === 'text') {
                return { content: `File: ${filePath}\n\n${value.content}`, error: null }
              } else if (value.type === 'error') {
                return { content: null, error: `Mandatory file error - ${filePath}: ${value.content}` }
              } else {
                return {
                  content: null,
                  error: `Mandatory file ${filePath} is of type '${value.type}' and cannot be included as text`,
                }
              }
            } else {
              return { content: null, error: `${filePath}: ${settled.reason?.toString()}` }
            }
          })
        } catch (e: any) {
          return [{ content: null, error: `Could not read files from ${entry}: ${e.message}` }]
        }
      }

      // Format 3 (default): explicit file path
      const settled = await readFileUnified({ relPath: entry, root: projectPath, interactor }).then(
        (value) => ({ status: 'fulfilled' as const, value }),
        (reason) => ({ status: 'rejected' as const, reason })
      )
      if (settled.status === 'fulfilled') {
        const value = settled.value as FileContent
        if (value.type === 'text') {
          return [{ content: `File: ${entry}\n\n${value.content}`, error: null }]
        } else if (value.type === 'error') {
          return [{ content: null, error: `Mandatory file error - ${entry}: ${value.content}` }]
        } else {
          return [
            {
              content: null,
              error: `Mandatory file ${entry} is of type '${value.type}' and cannot be included as text`,
            },
          ]
        }
      } else {
        const errorMsg = `${entry}: ${settled.reason?.toString()}`
        interactor.error(errorMsg)
        return [{ content: null, error: errorMsg }]
      }
    }

    // Process all entries
    const resolvedEntries = await Promise.all((withDocs.mandatoryDocs ?? []).map(resolveEntry))
    const processedFiles: DocResult[] = resolvedEntries.flat()

    // Build mandatory docs text from successful reads
    mandatoryDocText += processedFiles
      .filter((result) => result.content !== null)
      .map((result) => result.content)
      .join('\n\n')

    // Collect all errors as warnings
    const fileErrors = processedFiles
      .filter((result) => result.error !== null)
      .map((result) => `  - ${result.error}`)
      .join('\n')

    if (fileErrors) {
      warnings += fileErrors
    }

    if (mandatoryDocText) {
      formattedDocs += `\n\nMandatory documents

    Each of the following files are included entirely as deemed important.\n\n${mandatoryDocText}`
    }

    // Check all optional docs, log a warning if they are missing
    const optionalDocLines = await Promise.all(
      (withDocs.optionalDocs ?? []).map(async (doc) => {
        if (doc.path.startsWith('http') || (await pathExists(path.resolve(projectPath, doc.path)))) {
          return `  - ${doc.path}\n    ${doc.description}`
        } else {
          warnings += `\n  - Optional document not found: ${doc.path}`
          return undefined
        }
      })
    )
    let optionalDocsDescription = optionalDocLines.filter((text): text is string => !!text).join('\n')

    if (optionalDocsDescription) {
      formattedDocs += `\n\nOptional documents or resources to refer for more details:\n${optionalDocsDescription}`
    }

    if (warnings) {
      interactor.warn(`Regarding ${contextName}:\n${warnings}`)
    }

    return formattedDocs
  } catch (e: any) {
    console.error(`Could not format docs: ${e.message}`)
    return ''
  }
}

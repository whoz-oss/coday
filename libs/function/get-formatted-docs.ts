import { WithDocs } from '../model/with-docs'
import * as path from 'node:path'
import { Interactor } from '../model'
import { existsSync } from 'node:fs'
import { FileContent } from '../model/file-content'
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
    // Process mandatory documents
    const fileContents = await Promise.allSettled(
      (withDocs.mandatoryDocs ?? []).map(
        (docPath): Promise<FileContent> =>
          readFileUnified({
            relPath: docPath,
            root: projectPath,
            interactor,
          })
      )
    )
    // Process each file result
    const processedFiles = fileContents.map((settled, index) => {
      const filePath = withDocs.mandatoryDocs ? withDocs.mandatoryDocs[index] : 'no file path'

      if (settled.status === 'fulfilled') {
        const value = settled.value as FileContent
        // Process based on content type

        if (value.type === 'text') {
          return {
            content: `File: ${filePath}\n\n${value.content}`,
            error: null,
          }
        } else if (value.type === 'error') {
          // Important: log error for mandatory files
          const warningMessage = `Mandatory file error - ${filePath}: ${value.content}`
          return {
            content: null,
            error: warningMessage,
          }
        } else {
          // For other types (image, binary), create a warning
          const warningMessage = `Mandatory file ${filePath} is of type '${value.type}' and cannot be included as text`
          return {
            content: null,
            error: warningMessage,
          }
        }
      } else {
        // This should not happen with current readFileUnified implementation
        const errorMsg = `${filePath}: ${settled.reason?.toString()}`
        interactor.error(errorMsg)
        return {
          content: null,
          error: errorMsg,
        }
      }
    })

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
    let optionalDocsDescription = withDocs.optionalDocs
      ?.map((doc) => {
        if (doc.path.startsWith('http') || existsSync(path.resolve(projectPath, doc.path))) {
          return `  - ${doc.path}\n    ${doc.description}`
        } else {
          warnings += `\n  - Optional document not found: ${doc.path}`
        }
      })
      .filter((text) => !!text)
      .join('\n')

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

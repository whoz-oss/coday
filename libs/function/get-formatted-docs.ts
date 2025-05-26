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
    mandatoryDocText += fileContents
      .map((settled, index) => {
        const filePath = withDocs.mandatoryDocs ? withDocs.mandatoryDocs[index] : 'no file path'
        if (settled.status === 'fulfilled') {
          const value = settled.value as FileContent
          if (value.type === 'text') {
            return `File: ${filePath}\n\n${value.content}`
          }
        }
        return null
      })
      .join('\n\n')

    warnings += fileContents
      .map((settled, index) => {
        if (settled.status === 'rejected') {
          return `  - ${settled.reason?.toString()}`
        }
        if (settled.status === 'fulfilled') {
          const value = settled.value as FileContent
          if (value.type === 'error') {
            return `  - ${value.content.toString()}`
          }
        }
        return null
      })
      .filter((text) => !!text)
      .join('\n')

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
    console.error(`Could not format docs`)
    return ''
  }
}

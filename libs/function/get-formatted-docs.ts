import { WithDocs } from '../model/with-docs'
import * as path from 'node:path'
import { Interactor } from '../model'
import { existsSync } from 'node:fs'
import { readFileByPath } from './read-file-by-path'
import { FileContent } from '../model/file-content'

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
    if (withDocs.mandatoryDocs?.length) {
      mandatoryDocText += `\n\n## Mandatory documents
    
    Each of the following files are included entirely as deemed important.\n\n`
      const fileContents = await Promise.allSettled(
        withDocs.mandatoryDocs.map((docPath): Promise<FileContent> => {
          const fullPath = path.resolve(projectPath, docPath)
          if (!existsSync(fullPath)) {
            return Promise.resolve({
              type: 'error',
              content: `Mandatory document not found: ${docPath}`,
            })
          } else {
            return readFileByPath({
              relPath: docPath,
              root: '',
              interactor,
            })
          }
        })
      )
      mandatoryDocText += fileContents
        .map((settled, index) => {
          const filePath = withDocs.mandatoryDocs ? withDocs.mandatoryDocs[index] : 'no file path'
          if (settled.status === 'fulfilled' && settled.value.type === 'text') {
            return `File: ${filePath}\n\n${settled.value.content}`
          }
          return null
        })
        .join('\n\n')

      warnings += fileContents
        .map((settled, index) => {
          if (settled.status === 'rejected') {
            return `  - ${settled.reason?.toString()}`
          }
          if (settled.status === 'fulfilled' && settled.value.type === 'error') {
            return `  - ${settled.value.content}`
          }
          return null
        })
        .filter((text) => !!text)
        .join('\n')
    }
    formattedDocs += mandatoryDocText

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

import { WithDocs } from '../model/with-docs'
import * as path from 'node:path'
import { Interactor } from '../model'
import { readFileSync } from 'fs'
import { existsSync } from 'node:fs'

export function getFormattedDocs(withDocs: WithDocs, interactor: Interactor, projectPath: string): string {
  let formattedDocs = ''
  let mandatoryDocText = ''
  try {
    if (withDocs.mandatoryDocs?.length) {
      mandatoryDocText += `\n\n## Mandatory documents
    
    Each of the following files are included entirely as deemed important, path given as title`
      withDocs.mandatoryDocs.forEach((docPath) => {
        const fullPath = path.resolve(projectPath, docPath)
        if (existsSync(fullPath)) {
          const docContent = readFileSync(fullPath, 'utf-8')
          mandatoryDocText += `\n\n### ${docPath}\n\n${docContent}`
        } else {
          interactor.warn(`Mandatory document not found: ${docPath}`)
        }
      })
    }
    formattedDocs += mandatoryDocText

    // Check all optional docs, log a warning if they are missing
    let optionalDocsDescription = `\n\n## Optional documents to refer for more details:\n`
    if (withDocs.optionalDocs?.length) {
      let hasSomeValidDocs = false

      withDocs.optionalDocs.forEach((doc) => {
        const fullPath = path.resolve(projectPath, doc.path)
        if (existsSync(fullPath)) {
          optionalDocsDescription += `\n\n### ${doc.path}\n\n${doc.description}`
          hasSomeValidDocs = true
        } else {
          interactor.warn(`Optional document described as "${doc.description}" not found at path: ${doc.path}`)
        }
      })

      if (!hasSomeValidDocs) {
        optionalDocsDescription = ''
      }
    }
    formattedDocs += optionalDocsDescription

    return formattedDocs
  } catch (e: any) {
    console.error(`Could not format docs`)
    return ''
  }
}

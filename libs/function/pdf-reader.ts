import * as fs from 'fs'
import * as path from 'path'
import { Interactor } from '../model'

const pdfParse = require('pdf-parse')

export async function readPdfFile({
  relPath,
  root,
  interactor,
}: {
  relPath: string
  root: string
  interactor: Interactor
}): Promise<string> {
  try {
    const fullPath = path.join(root, relPath)

    if (!fs.existsSync(fullPath)) {
      const errorMessage = `PDF file not found at path: ${relPath}`
      interactor.error(errorMessage)
      return errorMessage
    }
    const dataBuffer = fs.readFileSync(fullPath)
    const pdfData = await pdfParse(dataBuffer)
    return pdfData.text
  } catch (error: any) {
    const errorMessage = `Error reading PDF file '${relPath}': ${error.message}`
    interactor.error(errorMessage)
    return errorMessage
  }
}

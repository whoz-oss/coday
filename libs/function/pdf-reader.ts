import * as fs from 'fs'
import * as path from 'path'
import { Interactor } from '@coday/model'
import { FileContent } from '@coday/model/file-content'
import PDFParser from 'pdf2json'

export async function readPdfFile({
  relPath,
  root,
  interactor,
}: {
  relPath: string
  root: string
  interactor: Interactor
}): Promise<FileContent> {
  try {
    const fullPath = path.join(root, relPath)

    if (!fs.existsSync(fullPath)) {
      const errorMessage = `PDF file not found at path: ${relPath}`
      interactor.error(errorMessage)
      return {
        type: 'error',
        content: errorMessage,
      }
    }

    // Read the PDF file
    const pdfData = await getPdfData(fullPath)

    // Extract text from the PDF data
    const textContent = extractTextFromPdfData(pdfData)

    return {
      type: 'text',
      content: textContent,
    }
  } catch (error: any) {
    const errorMessage = `Error reading PDF file '${relPath}': ${error.message}`
    interactor.error(errorMessage)
    return {
      type: 'error',
      content: errorMessage,
    }
  }
}

/**
 * Extract PDF data using pdf2json
 * @param filePath Path to the PDF file
 * @returns Promise that resolves with the PDF data
 */
function getPdfData(filePath: string): Promise<any> {
  return new Promise((resolve, reject) => {
    try {
      const pdfParser = new PDFParser()

      // Set up promise resolution
      pdfParser.on('pdfParser_dataReady', resolve)
      pdfParser.on('pdfParser_dataError', reject)

      // Load the PDF file
      pdfParser.loadPDF(filePath)
    } catch (err) {
      reject(err)
    }
  })
}

/**
 * Extract text from PDF data
 * @param pdfData PDF data from pdf2json
 * @returns Extracted text
 */
function extractTextFromPdfData(pdfData: any): string {
  let text = ''

  // Extract text from all pages
  if (pdfData && pdfData.Pages) {
    for (let i = 0; i < pdfData.Pages.length; i++) {
      const page = pdfData.Pages[i]
      let pageText = ''

      // Process each text element on the page
      if (page.Texts) {
        for (const textElement of page.Texts) {
          if (textElement.R) {
            for (const r of textElement.R) {
              // pdf2json encodes text in URI format, so we need to decode it
              const decodedText = decodeURIComponent(r.T)
              pageText += decodedText + ' '
            }
          }
        }
      }

      if (pageText) {
        text += pageText.trim() + '\n\n'
      }
    }
  }

  return text.trim()
}

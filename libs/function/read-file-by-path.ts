import { Interactor } from '../model'
import { FileContent } from '../model/file-content'
import * as path from 'path'
import { readFile } from 'node:fs/promises'

type ReadFileByPathInput = {
  relPath: string
  root: string
  interactor?: Interactor
}

export const readFileByPath = async (input: ReadFileByPathInput): Promise<FileContent> => {
  const { relPath, root, interactor } = input
  // need to prevent double slashes
  const fullPath = relPath ? path.resolve(root, relPath) : root

  try {
    interactor?.debug(`reading file ${fullPath}`)

    const content = (await readFile(fullPath)).toString()

    return {
      type: 'text',
      content: content,
    }
  } catch (err) {
    const errorMessage = `Error reading file ${fullPath}: ${err}`
    interactor?.error(errorMessage)
    console.error(err)

    return {
      type: 'error',
      content: errorMessage,
    }
  }
}

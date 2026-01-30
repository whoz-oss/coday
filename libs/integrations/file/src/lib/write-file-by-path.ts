import { existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs'
import { Interactor } from '@coday/model'
import * as path from 'path'

const SIZE_THRESHOLD_KB = 64
const SIZE_THRESHOLD_BYTES = SIZE_THRESHOLD_KB * 1024

type WriteFileByPathInput = {
  relPath: string
  root: string
  interactor?: Interactor
  content: string
}

export const writeFileByPath = ({ relPath, root, interactor, content }: WriteFileByPathInput) => {
  // need to prevent double slashes
  const fullPath = relPath ? path.resolve(root, relPath) : root

  try {
    const dir = path.dirname(fullPath)
    if (!existsSync(dir)) {
      interactor?.debug(`Making directory ${dir}`)
      mkdirSync(dir, { recursive: true })
    }

    if (existsSync(fullPath)) {
      const fileSize = statSync(fullPath).size
      if (fileSize > SIZE_THRESHOLD_BYTES) {
        const message = `File full write not accepted: ${fullPath} exceeds the size threshold of ${SIZE_THRESHOLD_KB}kB. `
        interactor?.warn(message)
        return message
      }
    }

    const data = new Uint8Array(Buffer.from(content))
    writeFileSync(fullPath, data)
    return 'File write success'
  } catch (err) {
    interactor?.error(`Error writing file ${fullPath}`)
    console.error(err)
    return 'Error writing file'
  }
}

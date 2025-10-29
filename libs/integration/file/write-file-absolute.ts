import { existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs'
import { Interactor } from '../../model/interactor'
import * as path from 'path'

const SIZE_THRESHOLD_KB = 18
const SIZE_THRESHOLD_BYTES = SIZE_THRESHOLD_KB * 1024

type WriteFileAbsoluteInput = {
  absolutePath: string
  interactor?: Interactor
  content: string
  skipSizeCheck?: boolean // For thread files, we might want to skip the size check
}

export const writeFileAbsolute = ({ absolutePath, interactor, content, skipSizeCheck }: WriteFileAbsoluteInput) => {
  try {
    const dir = path.dirname(absolutePath)
    if (!existsSync(dir)) {
      interactor?.debug(`Making directory ${dir}`)
      mkdirSync(dir, { recursive: true })
    }

    if (!skipSizeCheck && existsSync(absolutePath)) {
      const fileSize = statSync(absolutePath).size
      if (fileSize > SIZE_THRESHOLD_BYTES) {
        const message = `File full write not accepted: ${absolutePath} exceeds the size threshold of ${SIZE_THRESHOLD_KB}kB. `
        interactor?.warn(message)
        return message
      }
    }

    const data = new Uint8Array(Buffer.from(content))
    writeFileSync(absolutePath, data)
    return 'File write success'
  } catch (err) {
    interactor?.error(`Error writing file ${absolutePath}`)
    console.error(err)
    return 'Error writing file'
  }
}

import { existsSync, mkdirSync, renameSync } from 'node:fs'
import * as path from 'path'

type MoveFileInput = {
  fromAbsolute: string
  toAbsolute: string
}

type MoveFileResult = {
  success: boolean
  message: string
}

/**
 * Move a file from one absolute path to another.
 * - Fails if source does not exist.
 * - Fails if destination already exists (no overwrite).
 * - Creates intermediate directories at destination if needed.
 */
export const moveFile = ({ fromAbsolute, toAbsolute }: MoveFileInput): MoveFileResult => {
  if (!existsSync(fromAbsolute)) {
    return { success: false, message: `Source file not found: ${fromAbsolute}` }
  }

  if (existsSync(toAbsolute)) {
    return {
      success: false,
      message: `Destination already exists: ${toAbsolute}. Remove it explicitly before moving.`,
    }
  }

  const destDir = path.dirname(toAbsolute)
  if (!existsSync(destDir)) {
    mkdirSync(destDir, { recursive: true })
  }

  try {
    renameSync(fromAbsolute, toAbsolute)
    return { success: true, message: `File moved successfully` }
  } catch (error) {
    return { success: false, message: `Error moving file: ${error}` }
  }
}

import * as fs from 'fs'
import * as path from 'path'

type ListFilesInput = {
  relPath: string
  root: string
}

/**
 * Validates that the resolved path stays within the root directory
 */
const validatePathSecurity = (relPath: string, root: string): string => {
  const resolvedRoot = path.resolve(root)
  const fullPath = path.resolve(resolvedRoot, relPath)
  
  // Normalize paths to handle edge cases like double slashes, etc.
  const normalizedRoot = path.normalize(resolvedRoot)
  const normalizedFull = path.normalize(fullPath)
  
  // Check if the full path starts with root path + separator, or equals root exactly
  if (!normalizedFull.startsWith(normalizedRoot + path.sep) && normalizedFull !== normalizedRoot) {
    throw new Error(`Attempt to navigate outside the root folder: ${relPath} is not allowed`)
  }
  
  return fullPath
}

/**
 * Validates that a symbolic link points to an existing directory
 */
const validateSymlinkTarget = async (symlinkPath: string): Promise<void> => {
  try {
    const targetStat = await fs.promises.stat(symlinkPath)
    if (!targetStat.isDirectory()) {
      throw new Error(`Symbolic link target is not a directory: ${symlinkPath}`)
    }
  } catch (error: any) {
    if (error.code === 'ENOENT') {
      throw new Error(`Symbolic link target does not exist: ${symlinkPath}`)
    }
    throw error
  }
}

/**
 * Validates that a path exists and is accessible as a directory
 */
const validateDirectoryPath = async (fullPath: string): Promise<void> => {
  let stat: fs.Stats
  try {
    stat = await fs.promises.lstat(fullPath)
  } catch (error: any) {
    if (error.code === 'ENOENT') {
      throw new Error(`Directory does not exist: ${fullPath}`)
    }
    throw new Error(`Cannot access path ${fullPath}: ${error.message}`)
  }

  if (stat.isSymbolicLink()) {
    await validateSymlinkTarget(fullPath)
  } else if (!stat.isDirectory()) {
    throw new Error(`Path is not a directory: ${fullPath}`)
  }
}

/**
 * Safely gets file information, handling broken symlinks gracefully
 */
const getFileInfo = async (filePath: string, fileName: string): Promise<string> => {
  try {
    const stat = await fs.promises.lstat(filePath)
    return stat.isDirectory() ? `${fileName}/` : fileName
  } catch (error: any) {
    // If we can't stat a file (e.g., broken symlink), still include it but mark it
    return `${fileName} (inaccessible)`
  }
}

export const listFilesAndDirectories = async ({ relPath, root }: ListFilesInput): Promise<string[]> => {
  try {
    // Validate path security and get full path
    const fullPath = validatePathSecurity(relPath, root)
    

    // Validate that the path exists and is a directory (or valid symlink to directory)
    await validateDirectoryPath(fullPath)

    // Read directory contents
    const fileNames = await fs.promises.readdir(fullPath)
    
    // Get file information for each item, handling errors gracefully
    const result = await Promise.all(
      fileNames.map(fileName => {
        const filePath = path.join(fullPath, fileName)
        return getFileInfo(filePath, fileName)
      })
    )

    return result
    
  } catch (error: any) {
    const errorMsg = `Error listing directory: ${error.message || error}`
    throw new Error(errorMsg)
  }
}
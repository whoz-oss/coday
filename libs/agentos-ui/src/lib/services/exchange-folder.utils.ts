/**
 * Reason a folder name cannot be created, or null when the name is acceptable.
 *
 * A folder is created by its name inside the folder being browsed, so a name that carries a path separator
 * would silently build a nested path and `.` / `..` would address the folder itself or its parent.
 */
export function validateFolderName(name: string): string | null {
  const trimmed = name.trim()
  if (!trimmed) return 'Enter a folder name.'
  if (/[\\/]/.test(trimmed)) return 'A folder name cannot contain "/" or "\\".'
  if (trimmed === '.' || trimmed === '..') return '"." and ".." are not valid folder names.'
  return null
}

/** Last segment of a relative path. */
export function folderBaseName(path: string): string {
  return path.replace(/^\/+|\/+$/g, '').split('/').pop() ?? path
}

/**
 * Confirmation text of a folder deletion. The file count comes from the recursive manifest: while it is not known
 * (still loading, or the manifest failed) the wording stays accurate without it.
 */
export function folderDeleteMessage(name: string, fileCount: number | null): string {
  if (fileCount === null) return `Delete folder "${name}" and everything in it? This action cannot be undone.`
  if (fileCount === 0) return `Delete folder "${name}"? It contains no files. This action cannot be undone.`
  const files = fileCount === 1 ? '1 file' : `${fileCount} files`
  return `Delete folder "${name}" and its ${files}? This action cannot be undone.`
}

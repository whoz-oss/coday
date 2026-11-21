/** Longest folder name accepted by the UI (a common file-system limit). */
export const MAX_FOLDER_NAME_LENGTH = 255

/** [path] without its leading and trailing slashes. */
export function trimSlashes(path: string): string {
  return path.replace(/^\/+|\/+$/g, '')
}

/** [name] inside the folder [parent] (empty for the scope root). */
export function joinPath(parent: string, name: string): string {
  const folder = trimSlashes(parent)
  return folder ? `${folder}/${name}` : name
}

/** File name of the ZIP of the folder at [path]; [rootName] names the archive of a whole scope. */
export function archiveFilename(path: string, rootName: string): string {
  const last = trimSlashes(path).split('/').pop()
  return `${last || rootName}.zip`
}

/** Why [name] cannot be the name of a new folder, or null when it can. */
export function folderNameError(name: string): string | null {
  const trimmed = name.trim()
  if (!trimmed) return 'Enter a folder name.'
  if (trimmed.includes('/') || trimmed.includes('\\')) return 'A folder name cannot contain "/" or "\\".'
  if (trimmed === '.' || trimmed === '..') return 'This folder name is not allowed.'
  if (trimmed.length > MAX_FOLDER_NAME_LENGTH) return 'This folder name is too long.'
  return null
}

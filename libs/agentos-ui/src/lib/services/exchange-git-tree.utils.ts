import { DiffFile, GitFileStatus } from './exchange-environment.service'

export const GIT_FILE_LABELS: Record<GitFileStatus, { code: string; label: string }> = {
  ADDED: { code: 'A', label: 'Added' },
  MODIFIED: { code: 'M', label: 'Modified' },
  DELETED: { code: 'D', label: 'Deleted' },
  UNTRACKED: { code: 'U', label: 'Untracked' },
  TYPE_CHANGED: { code: 'T', label: 'Type changed' },
  CONFLICTED: { code: '!', label: 'Merge conflict' },
}

/** Paths in the Git response are relative to repo/, not to the outer document Exchange. */
export function indexGitChanges(changes: DiffFile[]) {
  const files = new Map(changes.map((file) => [`repo/${file.path}`, file]))
  const folders = new Map<string, number>()
  for (const path of files.keys()) {
    const segments = path.split('/')
    segments.pop()
    while (segments.length) {
      const parent = segments.join('/')
      folders.set(parent, (folders.get(parent) ?? 0) + 1)
      segments.pop()
    }
  }
  return { files, folders }
}

/** Keep deletions reachable even if their whole parent directory has disappeared from disk. */
export function deletedFilesInDirectory(
  changes: DiffFile[],
  directory: string,
  visibleFiles: string[],
  visibleFolders: string[]
): { path: string; name: string; status: GitFileStatus }[] {
  if (directory !== 'repo' && !directory.startsWith('repo/')) return []
  const prefix = `${directory}/`
  const existingFiles = new Set(visibleFiles)
  const existingFolders = new Set(visibleFolders)
  return changes.flatMap((file) => {
    const path = `repo/${file.path}`
    if (file.status !== 'DELETED' || !path.startsWith(prefix) || existingFiles.has(path)) return []
    const name = path.slice(prefix.length)
    const slash = name.indexOf('/')
    if (slash >= 0 && existingFolders.has(prefix + name.slice(0, slash))) return []
    return [{ path, name, status: file.status }]
  })
}

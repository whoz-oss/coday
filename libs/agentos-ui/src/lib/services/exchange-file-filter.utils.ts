/** Maximum number of matching files rendered in the drawer. */
export const MAX_FILTER_MATCHES = 500

export interface FileTreeRow {
  kind: 'folder' | 'file'
  path: string
  name: string
  depth: number
}

export interface FilteredFileTree {
  rows: FileTreeRow[]
  matchCount: number
  truncated: boolean
}

interface FolderNode {
  name: string
  path: string
  folders: Map<string, FolderNode>
  files: { name: string; path: string }[]
}

const newFolder = (name: string, path: string): FolderNode => ({ name, path, folders: new Map(), files: [] })
const byName = (a: { name: string }, b: { name: string }): number => a.name.localeCompare(b.name)

/** Match relative paths case-insensitively, retaining ancestors and sorting each level. */
export function filterFileTree(paths: readonly string[], query: string, limit = MAX_FILTER_MATCHES): FilteredFileTree {
  const needle = query.trim().toLowerCase()
  if (!needle) return { rows: [], matchCount: 0, truncated: false }
  const root = newFolder('', '')
  const seen = new Set<string>()
  for (const raw of paths) {
    const segments = raw.split('/').filter(Boolean)
    const path = segments.join('/')
    if (!path || seen.has(path) || !path.toLowerCase().includes(needle)) continue
    seen.add(path)
    let folder = root
    for (const [index, segment] of segments.slice(0, -1).entries()) {
      let child = folder.folders.get(segment)
      if (!child) {
        child = newFolder(segment, segments.slice(0, index + 1).join('/'))
        folder.folders.set(segment, child)
      }
      folder = child
    }
    folder.files.push({ name: segments[segments.length - 1] as string, path })
  }
  const rows: FileTreeRow[] = []
  let emittedFiles = 0
  const walk = (folder: FolderNode, depth: number): void => {
    for (const child of [...folder.folders.values()].sort(byName)) {
      if (emittedFiles >= limit) return
      rows.push({ kind: 'folder', path: child.path, name: child.name, depth })
      walk(child, depth + 1)
    }
    for (const file of [...folder.files].sort(byName)) {
      if (emittedFiles >= limit) return
      rows.push({ kind: 'file', path: file.path, name: file.name, depth })
      emittedFiles++
    }
  }
  walk(root, 0)
  return { rows, matchCount: seen.size, truncated: seen.size > limit }
}

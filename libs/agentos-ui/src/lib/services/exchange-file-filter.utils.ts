/**
 * Pure helper behind the namespace file filter: turns the flat recursive list of file paths into
 * the flattened, fully expanded tree of the files matching a query.
 *
 * No Angular, no HTTP — safe to import anywhere in the exchange UI.
 */

/** Maximum number of matching files rendered; more would flood a narrow drawer. */
export const MAX_FILTER_MATCHES = 500

/** One line of the filtered tree, in display order. `depth` is 0 for entries at the scope root. */
export interface FileTreeRow {
  kind: 'folder' | 'file'
  /** Path relative to the scope root, forward slashes. */
  path: string
  /** Last path segment. */
  name: string
  depth: number
}

export interface FilteredFileTree {
  rows: FileTreeRow[]
  /** Files matching the query, before the display limit is applied. */
  matchCount: number
  /** True when `matchCount` exceeds the limit and the tail of the tree was left out. */
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

/**
 * Files whose relative path contains `query` (case-insensitive), with every ancestor folder.
 *
 * Matching the full path covers the file name and the folder segments at once: a query that names a
 * folder keeps everything below it. Each level lists folders first, then files, alphabetically, and
 * every folder is expanded. The tree is cut after `limit` files, in display order, so a truncated
 * result never holds a folder without any file under it.
 */
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

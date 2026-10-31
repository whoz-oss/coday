/**
 * Server-side folder browser of the wizard (`GET /api/setup/browse`, `POST /api/setup/mkdir`).
 *
 * Safety rules, all enforced here so they are unit-testable:
 *  - only absolute paths; NUL bytes and relative paths are refused (no resolution against the
 *    server's cwd, which would be an accident waiting to happen);
 *  - the path is normalised (`..` segments collapsed) and resolved through `realpath`, so a symlink
 *    never makes the listing claim a path it is not in. Listing is ONE level deep: a symlink loop
 *    cannot make it spin;
 *  - directories only (files are never listed, their names are none of our business);
 *  - `mkdir` creates ONE directory under an existing one, from a plain name (no separators, no
 *    `..`), never recursively and never over an existing entry.
 *
 * The routes additionally disable both once the setup is done, and only answer loopback hosts.
 */

import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'

export class BrowseError extends Error {
  constructor(
    readonly status: 400 | 403 | 404 | 409,
    message: string
  ) {
    super(message)
  }
}

export interface BrowseEntry {
  name: string
  path: string
  /** True when the entry is a symlink (shown, resolved when opened). */
  symlink?: boolean
}

export interface BrowseResult {
  /** Resolved (real) path being listed. */
  path: string
  parent: string | null
  home: string
  entries: BrowseEntry[]
  /** Directories were hidden (dot-directories) or unreadable. */
  hiddenCount: number
}

export interface BrowseOptions {
  home?: string
  /** Include dot-directories. */
  showHidden?: boolean
}

export const defaultCodayFolder = (home: string = os.homedir()): string => path.join(home, 'Coday')

export function normalizeBrowsePath(requested: string | undefined, home: string): string {
  const raw = requested === undefined || requested === '' ? home : requested
  if (raw.includes('\0')) throw new BrowseError(400, 'Invalid path.')
  if (!path.isAbsolute(raw)) throw new BrowseError(400, 'The path must be absolute.')
  return path.normalize(raw)
}

export function browse(requested: string | undefined, options: BrowseOptions = {}): BrowseResult {
  const home = options.home ?? os.homedir()
  const normalized = normalizeBrowsePath(requested, home)

  let real: string
  try {
    real = fs.realpathSync(normalized)
  } catch {
    throw new BrowseError(404, 'This folder does not exist.')
  }
  let stat: fs.Stats
  try {
    stat = fs.statSync(real)
  } catch {
    throw new BrowseError(404, 'This folder does not exist.')
  }
  if (!stat.isDirectory()) throw new BrowseError(400, 'This path is not a folder.')

  let dirents: fs.Dirent[]
  try {
    dirents = fs.readdirSync(real, { withFileTypes: true })
  } catch {
    throw new BrowseError(403, 'This folder cannot be read.')
  }

  const entries: BrowseEntry[] = []
  let hiddenCount = 0
  for (const dirent of dirents) {
    const full = path.join(real, dirent.name)
    let isDir = dirent.isDirectory()
    const symlink = dirent.isSymbolicLink()
    if (symlink) {
      try {
        isDir = fs.statSync(full).isDirectory()
      } catch {
        isDir = false // dangling link or loop (ELOOP): not listed
      }
    }
    if (!isDir) continue
    if (!options.showHidden && dirent.name.startsWith('.')) {
      hiddenCount++
      continue
    }
    entries.push({ name: dirent.name, path: full, ...(symlink ? { symlink: true } : {}) })
  }
  entries.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }))

  const parent = path.dirname(real)
  return { path: real, parent: parent === real ? null : parent, home, entries, hiddenCount }
}

const FORBIDDEN_NAME = /[\\/\0]|^\.{1,2}$/

export function mkdir(parentPath: string, name: string): { path: string } {
  if (typeof name !== 'string' || name.trim() === '' || FORBIDDEN_NAME.test(name.trim()) || name.length > 255) {
    throw new BrowseError(400, 'Use a plain folder name (no slashes).')
  }
  const parent = normalizeBrowsePath(parentPath, os.homedir())
  let realParent: string
  try {
    realParent = fs.realpathSync(parent)
    if (!fs.statSync(realParent).isDirectory()) throw new Error('not a directory')
  } catch {
    throw new BrowseError(404, 'The parent folder does not exist.')
  }
  const target = path.join(realParent, name.trim())
  try {
    fs.mkdirSync(target) // not recursive: refuses to overwrite and to create a chain of folders
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code
    if (code === 'EEXIST') throw new BrowseError(409, 'This folder already exists.')
    if (code === 'EACCES' || code === 'EPERM') throw new BrowseError(403, 'Not allowed to create a folder here.')
    throw new BrowseError(400, 'The folder could not be created.')
  }
  return { path: target }
}

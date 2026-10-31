import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { browse, BrowseError, defaultCodayFolder, mkdir, normalizeBrowsePath } from './browse'

let root: string
beforeEach(() => {
  root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'browse-')))
  fs.mkdirSync(path.join(root, 'b-dir'))
  fs.mkdirSync(path.join(root, 'A-dir'))
  fs.mkdirSync(path.join(root, '.hidden'))
  fs.writeFileSync(path.join(root, 'file.txt'), 'x')
})
afterEach(() => fs.rmSync(root, { recursive: true, force: true }))

const failure = (fn: () => unknown): BrowseError => {
  try {
    fn()
  } catch (err) {
    return err as BrowseError
  }
  throw new Error('expected a BrowseError')
}

describe('browse', () => {
  it('lists directories only, sorted case-insensitively, and counts the hidden ones', () => {
    const result = browse(root, { home: root })
    expect(result.entries.map((e) => e.name)).toEqual(['A-dir', 'b-dir'])
    expect(result.hiddenCount).toBe(1)
    expect(result.path).toBe(root)
    expect(result.parent).toBe(path.dirname(root))
  })

  it('shows hidden directories on request', () => {
    expect(browse(root, { home: root, showHidden: true }).entries.map((e) => e.name)).toContain('.hidden')
  })

  it('defaults to the home directory', () => {
    expect(browse(undefined, { home: root }).path).toBe(root)
    expect(browse('', { home: root }).path).toBe(root)
  })

  it('rejects relative paths and NUL bytes (never resolved against the server cwd)', () => {
    expect(failure(() => browse('relative/path', { home: root })).status).toBe(400)
    expect(failure(() => browse('..', { home: root })).status).toBe(400)
    expect(failure(() => browse(`${root}/\0evil`, { home: root })).status).toBe(400)
  })

  it('collapses .. segments instead of escaping through them', () => {
    expect(normalizeBrowsePath(`${root}/b-dir/../A-dir`, root)).toBe(path.join(root, 'A-dir'))
    expect(browse(`${root}/b-dir/../A-dir`, { home: root }).path).toBe(path.join(root, 'A-dir'))
  })

  it('answers 404 for a missing path and 400 for a file', () => {
    expect(failure(() => browse(path.join(root, 'nope'), { home: root })).status).toBe(404)
    expect(failure(() => browse(path.join(root, 'file.txt'), { home: root })).status).toBe(400)
  })

  it('reports the REAL path of a symlinked folder, and flags symlinks in a listing', () => {
    if (process.platform === 'win32') return
    fs.symlinkSync(path.join(root, 'A-dir'), path.join(root, 'link'))
    expect(browse(path.join(root, 'link'), { home: root }).path).toBe(path.join(root, 'A-dir'))
    expect(browse(root, { home: root }).entries.find((e) => e.name === 'link')?.symlink).toBe(true)
  })

  it('does not spin on a symlink loop, and skips dangling links', () => {
    if (process.platform === 'win32') return
    fs.symlinkSync(root, path.join(root, 'A-dir', 'loop')) // A-dir/loop -> root
    fs.symlinkSync(path.join(root, 'b-dir'), path.join(root, 'b-dir', 'self')) // b-dir/self -> b-dir
    fs.symlinkSync(path.join(root, 'ghost'), path.join(root, 'dangling'))
    fs.symlinkSync(path.join(root, 'l1'), path.join(root, 'l2')) // l2 -> l1 -> l2 (ELOOP)
    fs.symlinkSync(path.join(root, 'l2'), path.join(root, 'l1'))

    const names = browse(root, { home: root }).entries.map((e) => e.name)
    expect(names).not.toContain('dangling')
    expect(names).not.toContain('l1')
    // One level only: opening the loop lists it once, and the answer is finite.
    expect(browse(path.join(root, 'A-dir', 'loop'), { home: root }).path).toBe(root)
  })

  it('has no parent at the filesystem root', () => {
    expect(browse(path.parse(root).root, { home: root }).parent).toBeNull()
  })

  it('proposes ~/Coday by default', () => {
    expect(defaultCodayFolder('/home/me')).toBe(path.join('/home/me', 'Coday'))
  })
})

describe('mkdir', () => {
  it('creates exactly one folder under an existing one', () => {
    const result = mkdir(root, 'Coday')
    expect(result.path).toBe(path.join(root, 'Coday'))
    expect(fs.statSync(result.path).isDirectory()).toBe(true)
  })

  it.each(['', '  ', '..', '.', 'a/b', 'a\\b', 'x\0y'])('rejects the name %j', (name) => {
    expect(failure(() => mkdir(root, name)).status).toBe(400)
  })

  it('never recurses and never overwrites', () => {
    expect(failure(() => mkdir(path.join(root, 'missing'), 'x')).status).toBe(404)
    expect(failure(() => mkdir(root, 'A-dir')).status).toBe(409)
  })

  it('rejects a relative parent', () => {
    expect(failure(() => mkdir('relative', 'x')).status).toBe(400)
  })
})

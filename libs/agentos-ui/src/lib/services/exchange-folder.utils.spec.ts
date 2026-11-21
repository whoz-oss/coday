import { folderBaseName, folderDeleteMessage, validateFolderName } from './exchange-folder.utils'

describe('validateFolderName', () => {
  it.each(['docs', 'my folder', 'v1.2', '.hidden', 'a-b_c', 'données'])('accepts %s', (name) => {
    expect(validateFolderName(name)).toBeNull()
  })

  it.each(['', '   ', '\t'])('asks for a name when it is blank (%j)', (name) => {
    expect(validateFolderName(name)).toBe('Enter a folder name.')
  })

  it.each(['a/b', '/a', 'a/', 'a\\b'])('refuses a path separator in %j', (name) => {
    expect(validateFolderName(name)).toContain('cannot contain')
  })

  it.each(['.', '..', ' .. '])('refuses the relative segment %j', (name) => {
    expect(validateFolderName(name)).toContain('not valid folder names')
  })
})

describe('folderBaseName', () => {
  it('is the last segment of the path', () => {
    expect(folderBaseName('docs')).toBe('docs')
    expect(folderBaseName('docs/specs')).toBe('specs')
    expect(folderBaseName('/docs/specs/')).toBe('specs')
  })
})

describe('folderDeleteMessage', () => {
  it('names the folder and its files', () => {
    expect(folderDeleteMessage('docs', 2)).toBe('Delete folder "docs" and its 2 files? This action cannot be undone.')
  })

  it('uses the singular for one file', () => {
    expect(folderDeleteMessage('docs', 1)).toBe('Delete folder "docs" and its 1 file? This action cannot be undone.')
  })

  it('says so when the folder is empty', () => {
    expect(folderDeleteMessage('docs', 0)).toBe('Delete folder "docs"? It contains no files. This action cannot be undone.')
  })

  it('stays accurate without a count', () => {
    expect(folderDeleteMessage('docs', null)).toBe(
      'Delete folder "docs" and everything in it? This action cannot be undone.'
    )
  })
})

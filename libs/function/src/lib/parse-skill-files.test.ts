import * as fsp from 'node:fs/promises'
import * as os from 'node:os'
import * as path from 'node:path'
import { Interactor } from '@coday/model'
import { parseSkillFiles } from './parse-skill-files'

/** Minimal mock that satisfies the Interactor surface used by parseSkillFiles */
function createMockInteractor() {
  return {
    warn: jest.fn(),
    debug: jest.fn(),
  } as unknown as Interactor & { warn: jest.Mock; debug: jest.Mock }
}

describe('parseSkillFiles', () => {
  let tmpDir: string
  let interactor: ReturnType<typeof createMockInteractor>

  beforeEach(async () => {
    tmpDir = await fsp.mkdtemp(path.join(os.tmpdir(), 'coday-skill-parse-test-'))
    interactor = createMockInteractor()
  })

  afterEach(async () => {
    await fsp.rm(tmpDir, { recursive: true, force: true })
  })

  /** Helper: write a SKILL.md inside a skill directory under tmpDir */
  async function writeSkill(skillDir: string, content: string): Promise<string> {
    const dir = path.join(tmpDir, skillDir)
    await fsp.mkdir(dir, { recursive: true })
    const filePath = path.join(dir, 'SKILL.md')
    await fsp.writeFile(filePath, content, 'utf-8')
    return filePath
  }

  it('parses a valid SKILL.md with name and description', async () => {
    const filePath = await writeSkill(
      'skills/greet',
      ['---', 'name: greet', 'description: Say hello', '---', 'Body content here'].join('\n')
    )

    const result = await parseSkillFiles([filePath], tmpDir, interactor)

    expect(result).toHaveLength(1)
    expect(result[0]).toMatchObject({
      name: 'greet',
      description: 'Say hello',
      path: filePath,
    })
    expect(interactor.warn).not.toHaveBeenCalled()
  })

  it('parses entrypoint from frontmatter', async () => {
    const dir = path.join(tmpDir, 'skills', 'deploy')
    await fsp.mkdir(dir, { recursive: true })
    await fsp.writeFile(path.join(dir, 'workflow.md'), '# Workflow', 'utf-8')
    const filePath = path.join(dir, 'SKILL.md')
    await fsp.writeFile(
      filePath,
      ['---', 'name: deploy', 'description: Deploy stuff', 'entrypoint: ./workflow.md', '---', 'Body'].join('\n'),
      'utf-8'
    )

    const result = await parseSkillFiles([filePath], tmpDir, interactor)

    expect(result).toHaveLength(1)
    expect(result[0]!.entrypoint).toBe('./workflow.md')
  })

  it('warns and ignores a file without frontmatter delimiters', async () => {
    const dir = path.join(tmpDir, 'skills', 'nofm')
    await fsp.mkdir(dir, { recursive: true })
    const filePath = path.join(dir, 'SKILL.md')
    await fsp.writeFile(filePath, 'Just plain text, no frontmatter', 'utf-8')

    const result = await parseSkillFiles([filePath], tmpDir, interactor)

    expect(result).toHaveLength(0)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining('no frontmatter'))
  })

  it('warns and ignores a file with missing name or description', async () => {
    const filePath = await writeSkill(
      'skills/noname',
      ['---', 'description: Missing name field', '---', 'Body'].join('\n')
    )

    const result = await parseSkillFiles([filePath], tmpDir, interactor)

    expect(result).toHaveLength(0)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining('missing required fields'))
  })

  it('warns and ignores a path to a nonexistent file', async () => {
    const missingPath = path.join(tmpDir, 'skills', 'ghost', 'SKILL.md')

    const result = await parseSkillFiles([missingPath], tmpDir, interactor)

    expect(result).toHaveLength(0)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining('not found'))
  })

  it('rejects path traversal on skill path', async () => {
    const result = await parseSkillFiles(['../../etc/passwd'], tmpDir, interactor)

    expect(result).toHaveLength(0)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining('path traversal'))
  })

  it('rejects path traversal on entrypoint', async () => {
    const filePath = await writeSkill(
      'skills/evil',
      ['---', 'name: evil', 'description: Evil skill', 'entrypoint: ../../../../etc/passwd', '---', 'Body'].join('\n')
    )

    const result = await parseSkillFiles([filePath], tmpDir, interactor)

    expect(result).toHaveLength(0)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining('entrypoint path traversal'))
  })

  it('resolves short name to skills/{name}/SKILL.md', async () => {
    await writeSkill(
      'skills/popcorn',
      ['---', 'name: popcorn', 'description: Make popcorn', '---', 'Pop pop pop'].join('\n')
    )

    const result = await parseSkillFiles(['popcorn'], tmpDir, interactor)

    expect(result).toHaveLength(1)
    expect(result[0]!.name).toBe('popcorn')
    expect(result[0]!.path).toBe(path.join(tmpDir, 'skills', 'popcorn', 'SKILL.md'))
  })

  it('respects maxSkillsInPrompt limit', async () => {
    const paths: string[] = []
    for (let i = 0; i < 10; i++) {
      const filePath = await writeSkill(
        `skills/skill${i}`,
        ['---', `name: skill${i}`, `description: Skill number ${i}`, '---', 'Body'].join('\n')
      )
      paths.push(filePath)
    }

    const result = await parseSkillFiles(paths, tmpDir, interactor, { maxSkillsInPrompt: 5 })

    expect(result).toHaveLength(5)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining('maxSkillsInPrompt'))
  })

  it('excludes files exceeding maxSkillFileBytes with a warning', async () => {
    const filePath = await writeSkill(
      'skills/huge',
      ['---', 'name: huge', 'description: Huge skill', '---', 'x'.repeat(2000)].join('\n')
    )

    const result = await parseSkillFiles([filePath], tmpDir, interactor, { maxSkillFileBytes: 50 })

    expect(result).toHaveLength(0)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining('exceeds maxSkillFileBytes'))
  })

  it('excludes skill when required binary is not found', async () => {
    const filePath = await writeSkill(
      'skills/needsbin',
      [
        '---',
        'name: needsbin',
        'description: Needs a binary',
        'requires:',
        '  bins:',
        '    - nonexistent_binary_xyz',
        '---',
        'Body',
      ].join('\n')
    )

    const result = await parseSkillFiles([filePath], tmpDir, interactor)

    expect(result).toHaveLength(0)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining("binary 'nonexistent_binary_xyz' not found"))
  })

  it('excludes skill when required env var is not set', async () => {
    const filePath = await writeSkill(
      'skills/needsenv',
      [
        '---',
        'name: needsenv',
        'description: Needs an env var',
        'requires:',
        '  env:',
        '    - NONEXISTENT_VAR_XYZ',
        '---',
        'Body',
      ].join('\n')
    )

    const result = await parseSkillFiles([filePath], tmpDir, interactor)

    expect(result).toHaveLength(0)
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining("env var 'NONEXISTENT_VAR_XYZ' not set"))
  })

  it('normalizes CRLF line endings and parses correctly', async () => {
    const dir = path.join(tmpDir, 'skills', 'crlf')
    await fsp.mkdir(dir, { recursive: true })
    const filePath = path.join(dir, 'SKILL.md')
    const content = '---\r\nname: crlf\r\ndescription: CRLF skill\r\n---\r\nBody with CRLF\r\n'
    await fsp.writeFile(filePath, content, 'utf-8')

    const result = await parseSkillFiles([filePath], tmpDir, interactor)

    expect(result).toHaveLength(1)
    expect(result[0]!.name).toBe('crlf')
  })

  it('retains only the first skill when duplicate names appear', async () => {
    const filePath1 = await writeSkill(
      'skills/dup1',
      ['---', 'name: samename', 'description: First one', '---', 'Body 1'].join('\n')
    )

    const filePath2 = await writeSkill(
      'skills/dup2',
      ['---', 'name: samename', 'description: Second one', '---', 'Body 2'].join('\n')
    )

    const result = await parseSkillFiles([filePath1, filePath2], tmpDir, interactor)

    expect(result).toHaveLength(1)
    expect(result[0]!.description).toBe('First one')
    expect(interactor.warn).toHaveBeenCalledWith(expect.stringContaining('duplicate name'))
  })
})

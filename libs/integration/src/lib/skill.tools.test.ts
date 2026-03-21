import * as fsp from 'node:fs/promises'
import * as os from 'node:os'
import * as path from 'node:path'
import type { Interactor, SkillMetadata, CommandContext } from '@coday/model'
import { SkillTools } from './skill.tools'

/** Minimal mock that satisfies the Interactor surface used by SkillTools */
function createMockInteractor() {
  return {
    warn: jest.fn(),
    debug: jest.fn(),
    events: { next: jest.fn(), pipe: jest.fn() },
  } as unknown as Interactor & { warn: jest.Mock; debug: jest.Mock }
}

/** Helper to extract the raw tool functions from a SkillTools instance */
async function getToolFunctions(skillTools: SkillTools) {
  const tools = await skillTools.getTools({} as CommandContext, [], 'test-agent')
  const listSkills = tools.find((t) => t.function.name.endsWith('__list_skills'))
  const loadSkill = tools.find((t) => t.function.name.endsWith('__load_skill'))
  return { listSkills, loadSkill, tools }
}

describe('SkillTools', () => {
  let tmpDir: string
  let interactor: ReturnType<typeof createMockInteractor>

  beforeEach(async () => {
    tmpDir = await fsp.mkdtemp(path.join(os.tmpdir(), 'coday-skill-tools-test-'))
    interactor = createMockInteractor()
  })

  afterEach(async () => {
    await fsp.rm(tmpDir, { recursive: true, force: true })
  })

  /** Helper: write a skill file and return its SkillMetadata */
  async function writeSkillFile(
    dirName: string,
    content: string,
    meta?: Partial<SkillMetadata>
  ): Promise<SkillMetadata> {
    const dir = path.join(tmpDir, 'skills', dirName)
    await fsp.mkdir(dir, { recursive: true })
    const filePath = path.join(dir, 'SKILL.md')
    await fsp.writeFile(filePath, content, 'utf-8')
    return {
      name: meta?.name ?? dirName,
      description: meta?.description ?? `Description for ${dirName}`,
      path: filePath,
      ...meta,
    }
  }

  it('load_skill returns body without frontmatter', async () => {
    const metadata = await writeSkillFile(
      'greet',
      ['---', 'name: greet', 'description: Say hello', '---', 'Hello, world!'].join('\n')
    )

    const skillTools = new SkillTools(interactor, [metadata], tmpDir)
    const { loadSkill } = await getToolFunctions(skillTools)

    const result = await loadSkill!.function.function({ name: 'greet' })
    expect(result).toBe('Hello, world!')
  })

  it('load_skill with entrypoint returns content of the pointed file', async () => {
    const dir = path.join(tmpDir, 'skills', 'deploy')
    await fsp.mkdir(dir, { recursive: true })
    await fsp.writeFile(
      path.join(dir, 'SKILL.md'),
      ['---', 'name: deploy', 'description: Deploy stuff', 'entrypoint: ./workflow.md', '---', 'Ignored body'].join(
        '\n'
      ),
      'utf-8'
    )
    await fsp.writeFile(path.join(dir, 'workflow.md'), 'Step 1: deploy the thing', 'utf-8')

    const metadata: SkillMetadata = {
      name: 'deploy',
      description: 'Deploy stuff',
      path: path.join(dir, 'SKILL.md'),
      entrypoint: './workflow.md',
    }

    const skillTools = new SkillTools(interactor, [metadata], tmpDir)
    const { loadSkill } = await getToolFunctions(skillTools)

    const result = await loadSkill!.function.function({ name: 'deploy' })
    expect(result).toBe('Step 1: deploy the thing')
  })

  it('load_skill returns error message listing available skills for unknown skill', async () => {
    const metadata = await writeSkillFile(
      'alpha',
      ['---', 'name: alpha', 'description: Alpha skill', '---', 'Body'].join('\n')
    )

    const skillTools = new SkillTools(interactor, [metadata], tmpDir)
    const { loadSkill } = await getToolFunctions(skillTools)

    const result = await loadSkill!.function.function({ name: 'nonexistent' })
    expect(result).toContain("Skill 'nonexistent' not found")
    expect(result).toContain('alpha')
  })

  it('load_skill rejects entrypoint path traversal', async () => {
    const dir = path.join(tmpDir, 'skills', 'evil')
    await fsp.mkdir(dir, { recursive: true })
    await fsp.writeFile(
      path.join(dir, 'SKILL.md'),
      ['---', 'name: evil', 'description: Evil skill', 'entrypoint: ../../../../etc/passwd', '---', 'Body'].join('\n'),
      'utf-8'
    )

    const metadata: SkillMetadata = {
      name: 'evil',
      description: 'Evil skill',
      path: path.join(dir, 'SKILL.md'),
      entrypoint: '../../../../etc/passwd',
    }

    const skillTools = new SkillTools(interactor, [metadata], tmpDir)
    const { loadSkill } = await getToolFunctions(skillTools)

    const result = await loadSkill!.function.function({ name: 'evil' })
    expect(result).toContain('path traversal')
  })

  it('load_skill replaces {baseDir} with actual skill directory', async () => {
    const dir = path.join(tmpDir, 'skills', 'templated')
    await fsp.mkdir(dir, { recursive: true })
    await fsp.writeFile(
      path.join(dir, 'SKILL.md'),
      ['---', 'name: templated', 'description: Uses baseDir', '---', 'Run script at {baseDir}/run.sh'].join('\n'),
      'utf-8'
    )

    const metadata: SkillMetadata = {
      name: 'templated',
      description: 'Uses baseDir',
      path: path.join(dir, 'SKILL.md'),
    }

    const skillTools = new SkillTools(interactor, [metadata], tmpDir)
    const { loadSkill } = await getToolFunctions(skillTools)

    const result = await loadSkill!.function.function({ name: 'templated' })
    expect(result).toBe(`Run script at ${dir}/run.sh`)
    expect(result).not.toContain('{baseDir}')
  })

  it('list_skills returns name and description of all skills', async () => {
    const meta1 = await writeSkillFile(
      'alpha',
      ['---', 'name: alpha', 'description: Alpha skill', '---', 'Body'].join('\n'),
      { description: 'Alpha skill' }
    )

    const meta2 = await writeSkillFile(
      'beta',
      ['---', 'name: beta', 'description: Beta skill', '---', 'Body'].join('\n'),
      { description: 'Beta skill' }
    )

    const skillTools = new SkillTools(interactor, [meta1, meta2], tmpDir)
    const { listSkills } = await getToolFunctions(skillTools)

    const result = await listSkills!.function.function({})
    expect(result).toContain('alpha')
    expect(result).toContain('Alpha skill')
    expect(result).toContain('beta')
    expect(result).toContain('Beta skill')
  })
})

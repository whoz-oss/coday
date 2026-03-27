import * as fs from 'node:fs/promises'
import * as path from 'node:path'
import { AssistantToolFactory, CodayTool, CommandContext, FunctionTool, Interactor, SkillMetadata } from '@coday/model'

/**
 * SkillTools — exposes `list_skills` and `load_skill` tools for on-demand skill loading.
 *
 * Instantiated directly in agent.service.ts (NOT in the factory registry) because
 * skills are per-agent by nature.
 */
export class SkillTools extends AssistantToolFactory {
  static readonly TYPE = 'SKILL' as const

  constructor(
    interactor: Interactor,
    private readonly skills: SkillMetadata[],
    private readonly basePath: string
  ) {
    super(interactor, SkillTools.TYPE)
  }

  protected async buildTools(_context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    if (this.skills.length === 0) {
      return []
    }

    const result: CodayTool[] = []

    // list_skills tool
    const listSkillsFunction = async () => {
      if (this.skills.length === 0) {
        return 'No skills available.'
      }

      const lines = this.skills.map((s) => `- ${s.name}: ${s.description}`)
      return `Available skills:\n${lines.join('\n')}`
    }

    const listSkillsTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: `${this.name}__list_skills`,
        description: 'List all available skills with their name and description.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: listSkillsFunction,
      },
    }
    result.push(listSkillsTool)

    // load_skill tool
    const loadSkillFunction = async ({ name }: { name: string }) => {
      const skill = this.skills.find((s) => s.name === name)

      if (!skill) {
        const available = this.skills.map((s) => s.name).join(', ')
        return `Skill '${name}' not found. Available skills: ${available}`
      }

      try {
        // If entrypoint is defined, load the file it points to (resolved relative to the SKILL.md directory)
        if (skill.entrypoint) {
          const skillDir = path.dirname(skill.path)
          const entrypointPath = path.resolve(skillDir, skill.entrypoint)
          const normalizedBasePath = path.resolve(this.basePath) + path.sep
          if (!entrypointPath.startsWith(normalizedBasePath) && entrypointPath !== path.resolve(this.basePath)) {
            return `Skill '${name}' has invalid entrypoint: path traversal detected`
          }
          let content = await fs.readFile(entrypointPath, 'utf-8')
          content = content.replaceAll('{baseDir}', path.dirname(skill.path))
          return content
        }

        // Otherwise, return the body of the SKILL.md (without frontmatter)
        const content = await fs.readFile(skill.path, 'utf-8')

        // Strip frontmatter: find the second `---` delimiter
        const secondDelimiterIndex = content.indexOf('\n---', 3)
        if (secondDelimiterIndex === -1) {
          return content
        }

        // Find the end of the `---` line
        const bodyStartIndex = content.indexOf('\n', secondDelimiterIndex + 1)
        if (bodyStartIndex === -1) {
          return ''
        }

        const body = content.slice(bodyStartIndex + 1).trim()
        return body.replaceAll('{baseDir}', path.dirname(skill.path))
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        return `Failed to load skill '${name}': ${message}`
      }
    }

    const loadSkillTool: FunctionTool<{ name: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__load_skill`,
        description:
          'Load the full instructions of a skill by name. Use this when you need the detailed workflow/instructions for a specific skill.',
        parameters: {
          type: 'object',
          properties: {
            name: {
              type: 'string',
              description: 'The name of the skill to load.',
            },
          },
          required: ['name'],
        },
        parse: JSON.parse,
        function: loadSkillFunction,
      },
    }
    result.push(loadSkillTool)

    return result
  }
}

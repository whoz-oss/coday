import { ProjectDescription } from '@coday/model'

export const DEFAULT_CODAY_YAML: ProjectDescription = {
  description: `Dummy description of the project, refer to docs for proper use.`,
  mandatoryDocs: [],
  optionalDocs: [],
  scripts: {
    example: {
      description:
        'Dummy description of the example script so that the LLM can get a grasp of what it does (so to understand when to use it), refer to docs for proper use.',
      command: 'echo "example script run with great success"',
      parametersDescription: 'Dummy description of parameters, refer to docs for proper use.',
    },
  },
  // AI providers are intentionally not included in the default template
  // Users can add them manually when needed
}

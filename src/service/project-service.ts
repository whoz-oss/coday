import {existsSync, readFileSync, writeFileSync} from 'fs'
import * as yaml from 'yaml'
import {ProjectDescription} from './project-description'

const CONFIG_FILENAME_YAML = 'coday.yaml'

export const loadOrInitProjectConfig = (projectPath: string): ProjectDescription | null => {
  const projectConfigPathYaml = `${projectPath}/${CONFIG_FILENAME_YAML}`
  let projectConfig: ProjectDescription

  if (!existsSync(projectConfigPathYaml) && !existsSync(projectConfigPathYaml)) {
    projectConfig = {
      description: `Dummy description of the project. Write here a description of the project with all that matters: purpose, users, technologies, frameworks, conventions, tools, architecture... You can also write the paths to some relevant files the LLM could get on the go.`,
      scripts: {
        example: {
          description: 'Dummy description of the example script so that the LLM can get a grasp of what it does (so to understand when to use it). Better long than short. Here it echoes a simple text.',
          command: 'echo "example script run with great success"'
        }
      }
    }
    const yamlConfig = yaml.stringify(projectConfig)
    writeFileSync(projectConfigPathYaml, yamlConfig)
  } else {
    const fileContent = readFileSync(projectConfigPathYaml, 'utf-8')
    projectConfig = yaml.parse(fileContent) as ProjectDescription
  }
  return projectConfig
}
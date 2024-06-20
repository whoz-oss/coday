import {existsSync, readFileSync, writeFileSync} from 'fs'
import * as yaml from 'yaml'
import {ProjectDescription} from './project-description'
import {findFilesByName} from '../function/find-files-by-name'
import {join} from 'path'
import {Interactor} from "../interactor";

const CONFIG_FILENAME_YAML = 'coday.yaml'

export const loadOrInitProjectConfig = async (projectPath: string, interactor: Interactor): Promise<ProjectDescription | null> => {
  let absoluteProjectConfigPath: string | null = null
  let projectConfig: ProjectDescription

  const foundFiles = await findFilesByName({text: CONFIG_FILENAME_YAML, root: projectPath})

  if (foundFiles.length > 1) {
    throw new Error(`Multiple files found for ${CONFIG_FILENAME_YAML}. Please ensure there is only one file with this name.`)
  }
  if (foundFiles.length === 1) {
    absoluteProjectConfigPath = join(projectPath, foundFiles[0])
  }

  if (!absoluteProjectConfigPath || (!existsSync(absoluteProjectConfigPath) && !existsSync(absoluteProjectConfigPath))) {
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
    const projectConfigPath = join(projectPath, CONFIG_FILENAME_YAML)
    writeFileSync(projectConfigPath, yamlConfig)
    interactor.displayText(`Project configuration created at: ${projectConfigPath}`)
  } else {
    const fileContent = readFileSync(absoluteProjectConfigPath, 'utf-8')
    projectConfig = yaml.parse(fileContent) as ProjectDescription
    interactor.displayText(`Project configuration used: ${absoluteProjectConfigPath}`)
  }
  return projectConfig
}
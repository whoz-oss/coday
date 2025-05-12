import { existsSync, readFileSync } from 'fs'
import * as yaml from 'yaml'
import { Interactor, ProjectDescription } from '../model'
import * as path from 'node:path'
import { findFilesByName } from '../function/find-files-by-name'
import { getFormattedDocs } from '../function/get-formatted-docs'
import { DEFAULT_CODAY_YAML } from './default-coday-yaml'

const CONFIG_FILENAME_YAML = 'coday.yaml'

export const loadOrInitProjectDescription = async (
  projectPath: string,
  interactor: Interactor,
  username: string
): Promise<ProjectDescription> => {
  const foundFiles = await findFilesByName({ text: CONFIG_FILENAME_YAML, root: projectPath })
  let absoluteProjectDescriptionPath: string | null = null
  let projectDescription: ProjectDescription | undefined

  if (foundFiles.length > 1) {
    throw new Error(
      `Multiple files found for ${CONFIG_FILENAME_YAML}. Please ensure there is only one file with this name.`
    )
  }
  if (foundFiles.length === 1) {
    absoluteProjectDescriptionPath = path.join(projectPath, foundFiles[0])
  }

  try {
    if (absoluteProjectDescriptionPath && existsSync(absoluteProjectDescriptionPath)) {
      const fileContent = readFileSync(absoluteProjectDescriptionPath, 'utf-8')
      projectDescription = yaml.parse(fileContent) as ProjectDescription
      interactor.displayText?.(`Project configuration used: ${absoluteProjectDescriptionPath}`)
    }
  } finally {
    if (!projectDescription) {
      const message = `No ${CONFIG_FILENAME_YAML} found in project folder: ${projectPath}. Using default configuration (not written to disk).`
      console.warn(message)
      interactor.warn?.(message)
      projectDescription = { ...DEFAULT_CODAY_YAML }
    }
    projectDescription.description += getFormattedDocs(projectDescription, interactor, projectPath)
    projectDescription.description += `\n\n## User\n\n    You are interacting with a human with username: ${username}`
  }

  return projectDescription
}

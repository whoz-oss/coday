import { existsSync, readFileSync } from 'fs'
import * as yaml from 'yaml'
import * as path from 'node:path'
import { findFilesByName } from '@coday/function'
import { getFormattedDocs } from '@coday/function'
import { DEFAULT_CODAY_YAML } from './default-coday-yaml'
import { UserData } from '@coday/model'
import { ProjectDescription } from '@coday/model'
import { Interactor } from '@coday/model'

const CONFIG_FILENAME_YAML = 'coday.yaml'

export const loadOrInitProjectDescription = async (
  projectPath: string,
  interactor: Interactor,
  userData: UserData
): Promise<ProjectDescription> => {
  const foundFiles = await findFilesByName({ text: CONFIG_FILENAME_YAML, root: projectPath })
  let absoluteProjectDescriptionPath: string | null = null
  let projectDescription: ProjectDescription | undefined

  if (foundFiles.length > 1) {
    console.log(`[LOAD_PROJECT_DESC] ERROR: Multiple ${CONFIG_FILENAME_YAML} files found in ${projectPath}`)
    throw new Error(
      `Multiple files found for ${CONFIG_FILENAME_YAML}. Please ensure there is only one file with this name.`
    )
  }
  if (foundFiles.length === 1) {
    absoluteProjectDescriptionPath = path.join(projectPath, foundFiles[0]!)
  }

  try {
    if (absoluteProjectDescriptionPath && existsSync(absoluteProjectDescriptionPath)) {
      const fileContent = readFileSync(absoluteProjectDescriptionPath, 'utf-8')
      projectDescription = yaml.parse(fileContent) as ProjectDescription
      console.log(
        `[LOAD_PROJECT_DESC] Loaded ${CONFIG_FILENAME_YAML}: ${projectDescription.agents?.length || 0} agents, ${projectDescription.description?.length || 0} chars desc`
      )
      interactor.displayText?.(`Project configuration used: ${absoluteProjectDescriptionPath}`)
    }
  } finally {
    if (!projectDescription) {
      const message = `No ${CONFIG_FILENAME_YAML} found in project folder: ${projectPath}. Using default configuration (not written to disk).`
      console.warn(message)
      interactor.warn?.(message)
      projectDescription = { ...DEFAULT_CODAY_YAML }
    }
    projectDescription.description += await getFormattedDocs(
      projectDescription,
      interactor,
      projectPath,
      CONFIG_FILENAME_YAML
    )
    // Enhanced user context building
    let userContext = `\n\n## User\n\n    You are interacting with a human with username: ${userData.username}`

    if (userData.bio) {
      userContext += `\n\n    User bio: ${userData.bio}`
    }
    projectDescription.description += userContext
  }

  return projectDescription
}

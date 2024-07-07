import {existsSync, readFileSync, writeFileSync} from "fs"
import * as yaml from "yaml"
import {ProjectDescription} from "../model/project-description"
import path, {join} from "path"
import {Interactor} from "../model/interactor"
import {findFilesByName} from "../function/find-files-by-name"

const CONFIG_FILENAME_YAML = "coday.yaml"

export const loadOrInitProjectDescription = async (projectPath: string, interactor: Interactor): Promise<ProjectDescription> => {
  let absoluteProjectDescriptionPath: string | null = null
  let projectDescription: ProjectDescription
  
  const foundFiles = await findFilesByName({text: CONFIG_FILENAME_YAML, root: projectPath})
  
  if (foundFiles.length > 1) {
    throw new Error(`Multiple files found for ${CONFIG_FILENAME_YAML}. Please ensure there is only one file with this name.`)
  }
  if (foundFiles.length === 1) {
    absoluteProjectDescriptionPath = join(projectPath, foundFiles[0])
  }
  
  if (!absoluteProjectDescriptionPath || (!existsSync(absoluteProjectDescriptionPath) && !existsSync(absoluteProjectDescriptionPath))) {
    projectDescription = {
      description: `Dummy description of the project, refer to docs for proper use.`,
      mandatoryDocs: [],
      optionalDocs: [],
      scripts: {
        example: {
          description: "Dummy description of the example script so that the LLM can get a grasp of what it does (so to understand when to use it), refer to docs for proper use.",
          command: "echo \"example script run with great success\"",
          parametersDescription: "Dummy description of parameters, refer to docs for proper use."
        }
      }
    }
    const yamlConfig = yaml.stringify(projectDescription)
    const projectConfigPath = join(projectPath, CONFIG_FILENAME_YAML)
    writeFileSync(projectConfigPath, yamlConfig)
    interactor.displayText(`Project configuration created at: ${projectConfigPath}`)
  } else {
    const fileContent = readFileSync(absoluteProjectDescriptionPath, "utf-8")
    projectDescription = yaml.parse(fileContent) as ProjectDescription
    interactor.displayText(`Project configuration used: ${absoluteProjectDescriptionPath}`)
  }
  
  // Read the mandatory docs and add them to the description, separated by their title
  if (projectDescription.mandatoryDocs?.length) {
    projectDescription.mandatoryDocs.forEach(docPath => {
      const fullPath = path.resolve(projectPath, docPath)
      if (existsSync(fullPath)) {
        const docContent = readFileSync(fullPath, "utf-8")
        projectDescription.description += `\n\n## ${docPath}\n\n${docContent}`
      } else {
        interactor.warn(`Mandatory document not found: ${docPath}`)
      }
    })
  }
  
  // Check all optional docs, log a warning if they are missing
  if (projectDescription.optionalDocs?.length) {
    let optionalDocsDescription = `\n\nOptional documents to refer for more details:\n`
    let hasValidOptionalDocs = false
    
    projectDescription.optionalDocs.forEach(doc => {
      const fullPath = path.resolve(projectPath, doc.path)
      if (existsSync(fullPath)) {
        optionalDocsDescription += `\n- **${doc.path}**: ${doc.description}`
        hasValidOptionalDocs = true
      } else {
        interactor.warn(`Optional document described as "${doc.description}" not found at path: ${doc.path}`)
      }
    })
    
    if (hasValidOptionalDocs) {
      projectDescription.description += optionalDocsDescription
    }
  }
  
  return projectDescription
}

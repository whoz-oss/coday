import {existsSync, readFileSync, writeFileSync} from "fs"
import * as yaml from "yaml"
import {DEFAULT_DESCRIPTION, Interactor, ProjectDescription} from "../model"
import path, {join} from "path"
import {findFilesByName} from "../function/find-files-by-name"
import {integrationService} from "./integration.service"
import {memoryService} from "./memory.service"
import {MemoryLevel} from "../model/memory"

const CONFIG_FILENAME_YAML = "coday.yaml"

export const loadOrInitProjectDescription = async (projectPath: string, interactor: Interactor, username: string): Promise<ProjectDescription> => {
  let absoluteProjectDescriptionPath: string | null = null
  let projectDescription: ProjectDescription
  
  const foundFiles = await findFilesByName({text: CONFIG_FILENAME_YAML, root: projectPath})
  
  if (foundFiles.length > 1) {
    throw new Error(`Multiple files found for ${CONFIG_FILENAME_YAML}. Please ensure there is only one file with this name.`)
  }
  if (foundFiles.length === 1) {
    absoluteProjectDescriptionPath = join(projectPath, foundFiles[0])
  }
  
  if (!absoluteProjectDescriptionPath || !existsSync(absoluteProjectDescriptionPath)) {
    // Then create a default file at project's root
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
    // Else load the found file
    const fileContent = readFileSync(absoluteProjectDescriptionPath, "utf-8")
    projectDescription = yaml.parse(fileContent) as ProjectDescription
    interactor.displayText(`Project configuration used: ${absoluteProjectDescriptionPath}`)
  }
  
  // Read the mandatory docs and add them to the description, separated by their title
  let mandatoryDocText = ""
  if (projectDescription.mandatoryDocs?.length) {
    mandatoryDocText += `\n\n## Mandatory documents
    
    Each of the following files are included entirely as deemed important, path given as title`
    projectDescription.mandatoryDocs.forEach(docPath => {
      const fullPath = path.resolve(projectPath, docPath)
      if (existsSync(fullPath)) {
        const docContent = readFileSync(fullPath, "utf-8")
        mandatoryDocText += `\n\n### ${docPath}\n\n${docContent}`
      } else {
        interactor.warn(`Mandatory document not found: ${docPath}`)
      }
    })
  }
  projectDescription.description += mandatoryDocText
  
  // Check all optional docs, log a warning if they are missing
  let optionalDocsDescription = `\n\n## Optional documents to refer for more details:\n`
  if (projectDescription.optionalDocs?.length) {
    let hasSomeValidDocs = false
    
    projectDescription.optionalDocs.forEach(doc => {
      const fullPath = path.resolve(projectPath, doc.path)
      if (existsSync(fullPath)) {
        optionalDocsDescription += `\n\n### ${doc.path}\n\n${doc.description}`
        hasSomeValidDocs = true
      } else {
        interactor.warn(`Optional document described as "${doc.description}" not found at path: ${doc.path}`)
      }
    })
    
    if (!hasSomeValidDocs) {
      optionalDocsDescription = ""
    }
  }
  projectDescription.description += optionalDocsDescription
  
  projectDescription.description += `\n\n## User
    
    You are interacting with a human with username: ${username}`
  
  const userMemories = integrationService.hasIntegration("MEMORY")
    ? memoryService.listMemories(MemoryLevel.USER).map(m => `  - ${m.title}\n    ${m.content}`)
    : null
  let userMemoryText = ""
  if (userMemories) {
    interactor.displayText(`Loaded ${userMemories.length} user memories`)
    if (userMemories.length) {
      userMemoryText = `\n\n## User memories
    
    Here are the information collected during previous chats with the user about him:\n
    ${userMemories.join("\n")}`
    }
  }
  
  const projectMemories = integrationService.hasIntegration("MEMORY")
    ? memoryService.listMemories(MemoryLevel.PROJECT).map(m => `  - ${m.title}\n    ${m.content}`)
    : null
  let projectMemoryText = ""
  if (projectMemories) {
    interactor.displayText(`Loaded ${projectMemories.length} project memories`)
    if (projectMemories.length) {
      projectMemoryText = `\n\n## Project memories
    
    Here are the information collected during previous chats with the user about the project:\n
    ${projectMemories.join("\n")}`
    }
  }
  
  const memoryNote = "\n\nYou are higly encouraged to reflect at the end of each request on the knowledge that could be gained from the collected information, formalize it as new or updated memories and store them."
  const memoryText = userMemoryText || projectMemoryText ? `${userMemoryText}${projectMemoryText}${memoryNote}`
    : integrationService.hasIntegration("MEMORY")
      ? `No previous memories available.\n\n${memoryNote}`
      : ""
  
  projectDescription.description += memoryText
  
  const projectAssistants = projectDescription.assistants ? [DEFAULT_DESCRIPTION, ...projectDescription.assistants] : undefined
  const projectAssistantReferences = projectAssistants?.map((a) => `${a.name} : ${a.description}`)
  
  const assistantText = projectAssistantReferences && projectAssistantReferences.length ? `\n\n## Assistants teamwork
                    Here the assistants available on this project (by name : description) : \n- ${projectAssistantReferences.join("\n- ")}\n

                    Rules:
                    - **Active delegation**: Always delegate parts of complex requests to the relevant assistants given their domain of expertise.
                    - **Coordinator**: ${DEFAULT_DESCRIPTION.name} coordinate the team and have a central role
                    - **Calling**: To involve an assistant in the thread, mention it with an '@' prefix on their name and explain what is expected from him. The called assistant will be called after the current run. Example: '... and by the way, @otherAssistant, check this part of the request'.`
    : ""
  
  projectDescription.description += assistantText
  
  return projectDescription
}

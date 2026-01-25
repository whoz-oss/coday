export interface WithDocs {
  /**
   * List of project paths to documents that will be loaded as part of the initial system instruction when creating a thread.
   *
   * Be cautious to not expose very large documents that would waste tokens on every new thread, focus on the common core of the project.
   */
  mandatoryDocs?: string[]

  /**
   * List of document file that will not be loaded initially but are mentioned in the initial thread system instruction.
   *
   * Should a file be relevant on a topic, the LLM is expected to read it when needed.
   */
  optionalDocs?: DocumentationFile[]
}

export type DocumentationFile = {
  /**
   * Project path to the document
   */
  path: string

  /**
   * Description of the content and added value of the file.
   * Can be a synthetic summary (to generate with AI first)...
   */
  description: string
}

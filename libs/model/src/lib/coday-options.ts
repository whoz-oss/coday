export interface CodayOptions {
  oneshot: boolean
  debug: boolean
  project?: string
  thread?: string
  prompts: string[]
  fileReadOnly: boolean
  configDir: string // Always defined with default value
  auth: boolean
  agentFolders: string[]
  noLog: boolean
  logFolder?: string
  forcedProject: boolean // true if --local is used
  baseUrl?: string // Base URL for generating absolute links (auto-detected from server port if not provided)
}

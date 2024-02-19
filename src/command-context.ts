type Task = {
    description: string
    key: string
    data?: any
}

type Openai = {
    threadId: string
}

export type CommandContext = {
    projectRootPath: string
    task?: Task
    sourceBranch?: string
    username: string
    openai?: Openai
}
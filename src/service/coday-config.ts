export type CodayConfig = {
    project: {
        [key: string]: Project
    }
    currentProject?: string
}

export type Project = {
    path: string
    integration: {
        [key in ApiName]?: ApiIntegration
    }
}

export type ApiIntegration = {
    apiUrl?: string
    username?: string
    apiKey?: string
}

export enum ApiName {
    GIT = "GIT",
    JIRA = "JIRA",
    OPENAI = "OPENAI",
    GITLAB = "GITLAB",
}
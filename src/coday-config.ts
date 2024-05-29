export type CodayConfig = {
    projectPaths: {
        [key: string]: string;
    }
    lastProject?: string
    apiKeys: {
        [key: string]: string;
    }
}
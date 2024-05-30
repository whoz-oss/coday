import {existsSync, readFileSync, writeFileSync} from 'fs';
import {ApiIntegration, ApiName, CodayConfig, Project} from './coday-config';
import os from "os";
import path from "path";
import {mkdirSync} from "node:fs";

const DATA_PATH: string = "/.coday"
const CONFIG_FILENAME = 'config.json'
const API_KEY_SUFFIX = "_API_KEY"

class ConfigService {
    private config: CodayConfig | null = null;
    private readonly configPath: string

    constructor() {
        const userInfo = os.userInfo()
        const codayPath = path.join(userInfo.homedir, DATA_PATH)
        this.configPath = `${codayPath}/${CONFIG_FILENAME}`
    }

    get lastProject() {
        this.initConfig()
        return this.config!.currentProject
    }

    get projectNames() {
        this.initConfig();
        return Object.keys(this.config!.project);
    }

    get integrations() {
        this.initConfig()
        const project = this.getProject()
        return project!.integration!
    }

    hasIntegration(name: ApiName): boolean {
        this.initConfig()
        return Object.keys(this.getProject()!.integration).includes(name)
    }

    addProject(projectName: string, projectPath: string) {
        this.initConfig();
        this.config!.project[projectName] = {path: projectPath, integration: {}};
        this.saveConfigFile();
    }

    selectProject(name: string): string {
        this.initConfig();
        const projectPath: string | undefined = this.config!.project[name]?.path
        if (!projectPath) {
            throw new Error("Invalid selection")
        }
        this.config!.currentProject = name;
        this.saveConfigFile();
        return projectPath;
    }

    resetProjectSelection(): void {
        this.initConfig();
        this.config!.currentProject = undefined;
        this.saveConfigFile();
    }

    getApiKey(keyName: string): string | undefined {
        if (!(keyName in ApiName)) {
            return undefined
        }
        const apiName: ApiName = keyName as unknown as ApiName
        const envApiKey: string | undefined = process.env[`${apiName}${API_KEY_SUFFIX}`]
        // shortcut if an env var is set for this typedKey
        if (envApiKey) {
            return envApiKey
        }

        const project: Project | undefined = this.getProject()
        if (!project) {
            return undefined
        }
        let integration: ApiIntegration | undefined = project.integration[apiName]
        if (!integration) {
            return undefined
        }
        return integration.apiKey
    }

    private initConfig() {
        if (!this.config) {
            const dir = path.dirname(this.configPath)
            if (!existsSync(dir)) {
                mkdirSync(dir, {recursive: true})
            }
            if (!existsSync(this.configPath)) {
                this.config = {
                    project: {},
                };
                this.saveConfigFile();
            } else {
                this.config = JSON.parse(readFileSync(this.configPath, 'utf-8')) as CodayConfig;
            }
        }
    }

    private saveConfigFile(): void {
        const json = JSON.stringify(this.config, null, 2);
        writeFileSync(this.configPath, json);
    }


    private getProject(): Project | undefined {
        this.initConfig()
        const projectName = this.config!.currentProject

        // shortcut if no selected project
        if (!projectName) {
            return undefined
        }
        return this.config!.project[projectName]
    }

    setIntegration(selectedName: ApiName, integration: ApiIntegration) {
        const project = this.getProject()
        if (!project) {
            return
        }
        project.integration[selectedName] = integration
        this.saveConfigFile()
    }

    getApiUrl(apiName: ApiName): string | undefined {
        const project: Project | undefined = this.getProject()
        if (!project) {
            return undefined
        }
        let integration: ApiIntegration | undefined = project.integration[apiName]
        if (!integration) {
            return undefined
        }
        return integration.apiUrl
    }

    getUsername(apiName: ApiName): string | undefined {
        const project: Project | undefined = this.getProject()
        if (!project) {
            return undefined
        }
        let integration: ApiIntegration | undefined = project.integration[apiName]
        if (!integration) {
            return undefined
        }
        return integration.username
    }
}

export const configService = new ConfigService()

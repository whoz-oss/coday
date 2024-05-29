import { existsSync, writeFileSync, readFileSync } from 'fs';
import { CodayConfig } from '../coday-config';
import {Interactor} from "../interactor";

export class ConfigService {
    private static readonly CONFIG_FILENAME = 'config.json';
    private config: CodayConfig | null = null;
    private readonly configPath: string

    constructor(private codayPath: string, private interactor: Interactor) {
        this.configPath = `${this.codayPath}/${ConfigService.CONFIG_FILENAME}`
    }

    get lastProject() {
        return this.getConfig().lastProject
    }

    get projectNames() {
        const config = this.getConfig();
        return Object.keys(config.projectPaths);
    }

    private loadConfigFromDisk(): CodayConfig {
        let config: CodayConfig;
        if (!existsSync(this.configPath)) {
            config = {
                projectPaths: {},
                apiKeys: {}
            };
            this.writeConfigFile(config);
        } else {
            config = JSON.parse(readFileSync(this.configPath, 'utf-8')) as CodayConfig;
        }
        return config;
    }

    getConfig(): CodayConfig {
        if (!this.config) {
            this.config = this.loadConfigFromDisk();
        }
        return this.config;
    }

    writeConfigFile(config: CodayConfig): void {
        const json = JSON.stringify(config, null, 2);
        writeFileSync(this.configPath, json);
        this.config = config; // Update the in-memory config
    }

    addProject(projectName: string, projectPath: string): CodayConfig {
        const config = this.getConfig();
        config.projectPaths[projectName] = projectPath;
        this.writeConfigFile(config);
        return config;
    }

    selectProject(name: string): string {
        const config = this.getConfig();
        const projectPath = config.projectPaths[name]
        if (!projectPath) {
            throw new Error("Invalid selection")
        }
        config.lastProject = name;
        this.writeConfigFile(config);
        return projectPath;
    }

    resetProjectSelection(): void {
        const config = this.getConfig();
        config.lastProject = undefined;
        this.writeConfigFile(config);
    }

    getApiKey(keyName: string): string {
        if (process.env[keyName]) {
            return process.env[keyName]!;
        }

        const config = this.getConfig();
        if (config.apiKeys && config.apiKeys[keyName]) {
            return config.apiKeys[keyName];
        }

        const apiKey = this.interactor.promptText(`API key for ${keyName} not set. Please enter it now to save it under ${this.configPath}`);
        config.apiKeys = config.apiKeys || {};
        config.apiKeys[keyName] = apiKey;
        this.writeConfigFile(config);
        return apiKey;
    }
}

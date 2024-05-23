import {Interactor} from '../interactor';
import {CommandHandler} from "./command-handler";
import {CommandContext} from '../command-context';
import {existsSync, writeFileSync} from "node:fs";
import {readFileSync} from "fs";

export class ProjectHandler extends CommandHandler {
    commandWord: string = 'project';
    description: string = 'handles project config related commands';

    constructor(private interactor: Interactor, private codayPath: string, private username: string) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        this.interactor.displayText("project does nothing for now...")
        return context
    }

    initContext() : CommandContext | null {
        const config = this.loadProjects()
        if (!Object.entries(config.projectPaths).length) { // no projects at all, force user define one
            this.interactor.displayText("No existing project, please define one by its name")
            return this.addProject()
        }
        if (!config.lastProject) { // projects but no previous selection
            // no last project selected, force selection of one
            const names = Object.entries(config.projectPaths).map(entry => entry[0])
            const selection = this.interactor.chooseOption(names, 'Selection: ','Choose an existing project by number, type "new" to create one')
            if (selection === 'new') {
                return this.addProject()
            }
            try {
                const index = parseInt(selection)
                return this.selectProject(names[index])
            } catch (_) {
                this.interactor.error("Invalid project selection")
                return null
            }
        }
        return this.selectProject(config.lastProject)
    }

    loadProjects(): CodayConfig {
        const projectConfigPath: string = `${this.codayPath}/config.json`
        let config: CodayConfig
        if (!existsSync(projectConfigPath)) {
            config = {
                projectPaths: {}
            }
            const json = JSON.stringify(config, null, 2)
            writeFileSync(projectConfigPath, json)
            this.interactor.displayText(`Project config created at : ${projectConfigPath}`)
        } else {
            config = JSON.parse(readFileSync(projectConfigPath, 'utf-8')) as CodayConfig
        }
        return config
    }

    addProject(): CommandContext | null {
        let config = this.loadProjects()
        const projectName = this.interactor.promptText("Project name")
        const projectPath = this.interactor.promptText("Project path, no trailing slash")
        config.projectPaths[projectName] = projectPath
        return this.selectProject(projectName, config)
    }

    resetProjectSelection(): void {
        let config = this.loadProjects()
        config.lastProject = undefined
        const json = JSON.stringify(config, null, 2)
        writeFileSync(`${this.codayPath}/config.json`, json )
    }

    selectProject(name: string, config?: CodayConfig): CommandContext | null {
        if (!config) {
            config = this.loadProjects()
        }
        if (!name && !config.lastProject) {
            this.interactor.error("No project selected nor known.")
            return null
        }

        const selectedPath = config.projectPaths[name]
        if (!selectedPath) {
            this.interactor.error("")
            return null
        }
        config.lastProject = name
        this.interactor.displayText(`Project ${name} selected`)
        const json = JSON.stringify(config, null, 2)
        writeFileSync(`${this.codayPath}/config.json`, json )

        return {
            projectRootPath: selectedPath,
            username: this.username,
            commandQueue: [],
            history: []
        }
    }
}

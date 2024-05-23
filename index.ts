import {LoadHandler, MainHandler, SaveHandler} from "./src/handler";
import {CommandContext} from "./src/command-context";
import os from 'os';
import {existsSync, mkdirSync, writeFileSync} from "node:fs";
import {Interactor} from "./src/interactor";
import {TerminalInteractor} from "./src/terminal-interactor";
import {ProjectHandler} from "./src/handler/project-handler";
import {readFileSync} from "fs";

const DATA_PATH: string = "/.coday/"
const MAX_ITERATIONS: number = 10

type ProjectConfig = {
    projectPaths: {
        [key: string]: string;
    }
    lastProject?: string
}

class Coday {

    codayPath: string
    userInfo: os.UserInfo<string>
    context: CommandContext | null = null
    mainHandler: MainHandler
    projectHandler: ProjectHandler

    constructor(private interactor: Interactor) {
        this.userInfo = os.userInfo()
        this.codayPath = this.initCodayPath(this.userInfo)
        this.projectHandler = new ProjectHandler(interactor, this.codayPath)
        this.mainHandler = new MainHandler(
            interactor,
            MAX_ITERATIONS,
            [
                new SaveHandler(interactor, this.codayPath),
                this.projectHandler,
            ],
        )
    }

    private loadProjects(): ProjectConfig {
        const projectConfigPath: string = `${this.codayPath}/projects.json`
        let config: ProjectConfig
        if (!existsSync(projectConfigPath)) {
            config = {
                projectPaths: {}
            }
            const json = JSON.stringify(config, null, 2)
            writeFileSync(projectConfigPath, json)
            this.interactor.displayText(`Project config created at : ${projectConfigPath}`)
        } else {
            config = JSON.parse(readFileSync(projectConfigPath, 'utf-8')) as ProjectConfig
        }
        return config
    }

    private addProject(): CommandContext | null {
        let config = this.loadProjects()
        const projectName = this.interactor.promptText("Project name")
        const projectPath = this.interactor.promptText("Project path, no trailing slash")
        config.projectPaths[projectName] = projectPath
        return this.selectProject(projectName, config)
    }

    private resetProjectSelection(): void {
        let config = this.loadProjects()
        config.lastProject = undefined
        const json = JSON.stringify(config, null, 2)
        writeFileSync(`${this.codayPath}/projects.json`, json )
    }

    private selectProject(name: string, config?: ProjectConfig): CommandContext | null {
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
        writeFileSync(`${this.codayPath}/projects.json`, json )

        return {
            projectRootPath: selectedPath,
            username: this.userInfo.username,
            commandQueue: [],
            history: []
        }
    }

    async run(): Promise<void> {
        // Main loop to keep waiting for user input
        do {
            // initiate context in loop for when context is cleared
            if (!this.context) {
                const config = this.loadProjects()

                if (!Object.entries(config.projectPaths).length) { // no projects at all, force user define one
                    this.interactor.displayText("No existing project, please define one by its name")
                    this.context = this.addProject()
                    continue
                }
                if (!config.lastProject) { // projects but no previous selection
                    // no last project selected, force selection of one
                    const names = Object.entries(config.projectPaths).map(entry => entry[0])
                    const selection = this.interactor.chooseOption(names, 'Selection: ','Choose an existing project by number, type "new" to create one')
                    if (selection === 'new') {
                        this.context = this.addProject()
                        continue
                    }
                    try {
                        const index = parseInt(selection)
                        this.context = this.selectProject(names[index])
                        continue
                    } catch (_) {
                        this.interactor.error("Invalid project selection")
                        this.context = null
                        continue
                    }
                }
                this.context = this.selectProject(config.lastProject)
                continue
            }
            // allow user input
            this.interactor.addSeparator()
            const userCommand = this.interactor.promptText(`${this.userInfo.username}`)
            this.interactor.addSeparator()

            // quit loop if user wants to exit
            if (userCommand === this.mainHandler.exitWord) {
                break;
            }
            if (userCommand === this.mainHandler.resetWord) {
                this.context = null
                this.resetProjectSelection()
                continue
            }

            // add the user command to the queue and let handlers decompose it in many and resolve them ultimately
            this.context.commandQueue.push(userCommand)

            this.context = await this.mainHandler.handle(userCommand, this.context)

        } while (true)
    }

    private initCodayPath(userInfo: os.UserInfo<string>): string {
        // define data path to store settings and other
        const codayPath = `${userInfo.homedir}${DATA_PATH}`

        try {
            if (!existsSync(codayPath)) {
                mkdirSync(codayPath, {recursive: true});
                this.interactor.displayText(`Coday data folder created at: ${codayPath}`);
            } else {
                this.interactor.displayText(`Coday data folder used: ${codayPath}`);
            }
        } catch (error) {
            this.interactor.error(`Error creating directory: ${error}`);
        }
        return codayPath
    }
}

new Coday(new TerminalInteractor()).run()
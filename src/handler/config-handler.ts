import {Interactor} from '../interactor';
import {CommandHandler} from './command-handler';
import {CommandContext} from '../command-context';
import {ConfigService} from '../service/config-service';
import {loadOrInitProjectConfig} from "../service/project-service";

export class ConfigHandler extends CommandHandler {
    commandWord: string = 'config';
    description: string = 'handles config related commands';

    constructor(private interactor: Interactor, private configService: ConfigService, private username: string) {
        super();
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const cmd = this.getSubCommand(command)
        let result: CommandContext | null = context
        if (!cmd) {
            this.interactor.displayText(`${this.commandWord} can accept sub-commands: add-project, select-project.`);
        }
        if (cmd === "add-project") {
            result = this.addProject()
        }
        if (cmd === "select-project") {
            result = this.chooseProject()
        }

        if (!result) {
            throw new Error("Context lost in the process")
        }
        return result
    }

    initContext(): CommandContext | null {
        if (!this.configService.projectNames.length) { // no projects at all, force user define one
            this.interactor.displayText("No existing project, please define one by its name");
            return this.addProject();
        }
        const lastProject = this.configService.lastProject
        if (!lastProject) { // projects but no previous selection
            // no last project selected, force selection of one
            return this.chooseProject()
        }
        return this.selectProject(lastProject);
    }

    private chooseProject(): CommandContext|null {
        const names = this.configService.projectNames
        const selection = this.interactor.chooseOption(names, 'Selection: ', 'Choose an existing project by number, type "new" to create one');
        if (selection === 'new') {
            return this.addProject();
        }
        try {
            const index = parseInt(selection);
            return this.selectProject(names[index]);
        } catch (_) {
            this.interactor.error("Invalid project selection");
            return null;
        }
    }

    private addProject(): CommandContext | null {
        const projectName = this.interactor.promptText("Project name");
        const projectPath = this.interactor.promptText("Project path, no trailing slash");
        this.configService.addProject(projectName, projectPath);
        return this.selectProject(projectName);
    }

    resetProjectSelection(): void {
        this.configService.resetProjectSelection();
    }

    private selectProject(name: string): CommandContext | null {
        if (!name && !this.configService.lastProject) {
            this.interactor.error("No project selected nor known.");
            return null;
        }
        let projectPath: string
        try {
            projectPath = this.configService.selectProject(name)
        } catch (err: any) {
            this.interactor.error(err.message)
            return null
        }
        if (!projectPath) {
            this.interactor.error(`No path found to project ${name}`)
            return null
        }

        const projectConfig = loadOrInitProjectConfig(projectPath)

        this.interactor.displayText(`Project ${name} selected`);

        return {
            project: {
                root: projectPath,
                description: projectConfig?.description,
                scripts: projectConfig?.scripts
            },
            username: this.username,
            commandQueue: [],
            history: []
        }
    }
}

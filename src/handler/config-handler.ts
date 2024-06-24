import {Interactor} from '../interactor';
import {CommandHandler} from './command-handler';
import {CommandContext} from '../command-context';
import {loadOrInitProjectConfig} from "../service/project-service";
import {configService} from "../service/config-service";
import {ApiIntegration, ApiName} from "../service/coday-config";

export class ConfigHandler extends CommandHandler {
    commandWord: string = 'config';
    description: string = 'handles config related commands';

    constructor(private interactor: Interactor, private username: string) {
        super();
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const cmd = this.getSubCommand(command)
        let result: CommandContext | null = context
        if (!cmd) {
            this.interactor.displayText(`${this.commandWord} can accept sub-commands: add-project, select-project, edit-integration.`);
        }
        if (cmd === "add-project") {
            result = await this.addProject()
        }
        if (cmd === "select-project") {
            result = await this.chooseProject()
        }
        if (cmd === "edit-integration") {
            // nothing to do on the context here
            this.editIntegration()
        }

        if (!result) {
            throw new Error("Context lost in the process")
        }
        return result
    }

    /**
     * Initialize the CommandContext when starting Coday interactive loop.
     */
    async initContext(initialProject: string | undefined): Promise<CommandContext | null> {
        if (initialProject) {
            return await this.selectProject(initialProject)
        }

        if (!configService.projectNames.length) { // no projects at all, force user define one
            this.interactor.displayText("No existing project, please define one by its name");
            return this.addProject();
        }
        const lastProject = configService.lastProject
        if (!lastProject) { // projects but no previous selection
            // no last project selected, force selection of one
            return await this.chooseProject()
        }
        return await this.selectProject(lastProject);
    }

    private async chooseProject(): Promise<CommandContext|null> {
        const names = configService.projectNames
        const selection = this.interactor.chooseOption(names, 'Selection: ', 'Choose an existing project by number, type "new" to create one');
        if (selection === 'new') {
            return this.addProject();
        }
        try {
            const index = parseInt(selection);
            return await this.selectProject(names[index]);
        } catch (_) {
            this.interactor.error("Invalid project selection");
            return null;
        }
    }

    private async addProject(): Promise<CommandContext | null> {
        const projectName = this.interactor.promptText("Project name");
        const projectPath = this.interactor.promptText("Project path, no trailing slash");
        configService.addProject(projectName, projectPath);
        return await this.selectProject(projectName);
    }

    resetProjectSelection(): void {
        configService.resetProjectSelection();
    }

    private async selectProject(name: string): Promise<CommandContext | null> {
        if (!name && !configService.lastProject) {
            this.interactor.error("No project selected nor known.");
            return null;
        }
        let projectPath: string
        try {
            projectPath = configService.selectProject(name)
        } catch (err: any) {
            this.interactor.error(err.message)
            return null
        }
        if (!projectPath) {
            this.interactor.error(`No path found to project ${name}`)
            return null
        }

        const projectConfig = await loadOrInitProjectConfig(projectPath, this.interactor)

        this.interactor.displayText(`Project ${name} selected`);

        return new CommandContext({
            ...projectConfig,
            root: projectPath,
        }, this.username)
    }

    private editIntegration() {
        if (!configService.lastProject) {
            this.interactor.displayText("No current project, select one first.")
        }
        // Mention all available integrations
        const apiNames = Object.keys(ApiName)

        // List all set integrations and prompt to choose one to edit (or type name of wanted one)
        const currentIntegrations = configService.integrations
        const existingIntegrationNames: ApiName[] = currentIntegrations ? Object.keys(currentIntegrations) as ApiName[] : []
        const answer = this.interactor.chooseOption(existingIntegrationNames, "Select an integration to edit by number (or type name of integration to add or nothing to escape): ", `Integrations are tools behind some commands and/or functions for AI. Available ones are ${apiNames.join(', ')}.\nHere are the configured ones:`).toUpperCase()
        if (!answer) {
            return
        }
        const choice = parseInt(answer)
        let apiIntegration: ApiIntegration = {}
        let selectedName: ApiName
        if (!!choice) { // choice is a number
            if (choice >= 0 && choice < existingIntegrationNames.length) {
                this.interactor.warn("Number selection out of available options.")
                return
            }
            selectedName = existingIntegrationNames[choice - 1]
            apiIntegration = currentIntegrations[selectedName]!
        } else { // answer is a text
            if (existingIntegrationNames.includes(answer as ApiName)) {
                apiIntegration = currentIntegrations[answer as ApiName]!
            } else if (!apiNames.includes(answer)) {
                this.interactor.warn("Name input not among available options.")
                return
            } // then answer is one of the non-selected yet integrations
            selectedName = answer as ApiName
        }

        // take all fields with existing values if available
        const apiUrl = this.interactor.promptText("Api url (if applicable)", apiIntegration.apiUrl)
        const username = this.interactor.promptText("username (if applicable)", apiIntegration.username)
        const apiKey = this.interactor.promptText("Api key (if applicable)", apiIntegration.apiKey) // TODO see another way to update an api key ?

        configService.setIntegration(selectedName, {apiUrl, username, apiKey})
    }
}

import {CommandHandler} from './command-handler';
import {CommandContext} from '../command-context';
import {Interactor} from '../interactor';
import {existsSync, writeFileSync} from 'fs';

type ProjectConfig = {
    projectPaths: Map<string, string>
    lastProject?: string
}

export class ProjectLoadHandler extends CommandHandler {
    commandWord: string = 'load';
    description: string = 'loads the given project, or the previous one if any, or force first project creation';

    constructor(private interactor: Interactor, private readonly codayPath: string) {
        super();
        this.codayPath = codayPath;
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        // load the config

        // if no last project
        // if
        const projectRoot = '/path/to/project/root'; // Or implement logic to dynamically set this
        this.interactor.displayText(`Project root set to: ${projectRoot}`);
        return context;
    }

}

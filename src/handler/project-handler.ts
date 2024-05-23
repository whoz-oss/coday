import {NestedHandler} from './nested-handler';
import {Interactor} from '../interactor';
import {ProjectLoadHandler} from './project-load-handler';

export class ProjectHandler extends NestedHandler {
    commandWord: string = 'project';
    description: string = 'handles project config related commands';

    constructor(interactor: Interactor, codayPath: string) {
        super(interactor);
        this.handlers = [
            new ProjectLoadHandler(interactor, codayPath)
        ];
    }
}

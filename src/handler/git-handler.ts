import { NestedHandler } from './nested-handler';
import { Interactor } from '../interactor';
import { GitDefaultHandler } from './git-default-handler'; // Import the new handler

export class GitHandler extends NestedHandler {
    commandWord: string = 'git';
    description: string = 'handles git-related commands';

    constructor(interactor: Interactor) {
        super(interactor);
        this.handlers = [
            new GitDefaultHandler(interactor) // IMPORTANT to keep in last position
        ]
    }

}

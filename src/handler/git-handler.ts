import { NestedHandler } from './nested-handler';
import { Interactor } from '../interactor';

export class GitHandler extends NestedHandler {
    commandWord: string = 'git';
    description: string = 'handles git-related commands';

    constructor(private interactor: Interactor) {
        super();
    }

    // You can add methods and inner handlers later as needed
}

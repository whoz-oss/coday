import {GitBranchHandler} from "./git-branch-handler";
import {CommandHandler} from "./command-handler";
import {JiraHandler} from "./jira-handler";

export const handlers: CommandHandler[] = [
    new GitBranchHandler(),
    new JiraHandler()
]
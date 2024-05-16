import {CommandHandler} from "./command-handler";
import axios from "axios";
import {CommandContext} from "./command-context";
import {Interactor} from "./interactor";


const jiraDomain = 'https://whoz.atlassian.net'
const username = 'vincent.audibert@whoz.com'
const apiToken = process.env['JIRA_API_TOKEN']

// Basic Auth string of username and API token
const authString = Buffer.from(`${username}:${apiToken}`).toString('base64');

export class JiraHandler extends CommandHandler {
    commandWord: string = "jira"
    description: string = "sources an issue, usage: jira wz-1234"

    constructor(private interactor: Interactor) {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        if (!apiToken) {
            this.interactor.warn("JIRA_API_TOKEN is not set in the environment")
            return context
        }

        const subCommand = this.getSubCommand(command)
        console.log("got sub-command: ", subCommand)
        try {
            const response = await axios.get(`${jiraDomain}/rest/api/2/issue/${subCommand}`, {
                headers: {
                    'Authorization': `Basic ${authString}`,
                    'Accept': 'application/json'
                }
            });

            const {
                key,
                self,
                fields: {
                    parent,
                    customfield_10582: squad,
                    priority,
                    labels,
                    status,
                    customfield_10564: customers,
                    issuetype,
                    description,
                    summary,
                    duedate
                }
            } = response.data
            const issue = {
                key,
                self,
                summary,
                description,
                priority: priority?.name,
                customers: customers ?? [],
                duedate,
                issuetype: issuetype?.name,
                squad: squad?.value,
                parent: {id: parent?.id, key: parent?.key},
                labels,
                status: status.name
            }
            console.log("Read issue, got: ", issue)

            // TODO: ask ChatGPT for a task description
            return {
                ...context, task: {
                    description: issue.summary + "\n" + issue.description, // TODO: replace this by better
                    data: issue,
                    key
                },
                history: [...context.history, { command, response: issue.summary }]
            }
        } catch (error) {
            console.error('Error fetching JIRA issue:', error);
            throw error;
        }
    }

}
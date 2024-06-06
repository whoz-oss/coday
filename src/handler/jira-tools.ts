import {Context} from "../context"
import {retrieveJiraTicket} from "../function"
import {RunnableToolFunction} from "openai/lib/RunnableFunction"
import {AssistantToolFactory, Tool} from "./init-tools"
import {Interactor} from "../interactor"
import {Beta} from "openai/resources"
import {configService} from "../service/config-service";
import {ApiName} from "../service/coday-config";
import AssistantTool = Beta.AssistantTool;

export class JiraTools extends AssistantToolFactory {

    constructor(interactor: Interactor) {
        super(interactor)
    }

    protected hasChanged(context: Context): boolean {
        return this.lastToolInitContext?.project.root !== context.project.root
    }

    protected buildTools(context: Context): Tool[] {
        const result: Tool[] = []
        if (!configService.hasIntegration(ApiName.JIRA)) {
            return result
        }

        const jiraBaseUrl = configService.getApiUrl(ApiName.JIRA)
        const jiraUsername = configService.getUsername(ApiName.JIRA)
        const jiraApiToken = configService.getApiKey(ApiName.JIRA)
        if (!(jiraBaseUrl && jiraUsername && jiraApiToken)) {
            return result
        }
        const retrieveTicket = ({ticketId}: { ticketId: string }) => {
            return retrieveJiraTicket(ticketId, jiraBaseUrl, jiraApiToken, jiraUsername, this.interactor)
        }
        const retrieveJiraTicketFunction: AssistantTool & RunnableToolFunction<{
            ticketId: string,
            jiraBaseUrl: string,
            jiraApiToken: string,
            jiraUsername: string
        }> = {
            type: "function",
            function: {
                name: "retrieveJiraTicket",
                description: "Retrieve Jira ticket details by ticket ID.",
                parameters: {
                    type: "object",
                    properties: {
                        ticketId: {type: "string", description: "Jira ticket ID"},
                    }
                },
                parse: JSON.parse,
                function: retrieveTicket
            }
        }

        result.push(retrieveJiraTicketFunction)

        return result
    }
}

import { Agent, CommandContext, Interactor } from '../../model'
import { AssistantToolFactory, CodayTool } from '../assistant-tool-factory'
import { FunctionTool } from '../types'
import { AgentService } from '../../agent'
import { filter, lastValueFrom, Observable, tap } from 'rxjs'
import { CodayEvent, MessageEvent } from '../../shared'
import { AiThread } from '../../ai-thread/ai-thread'

export class AiTools extends AssistantToolFactory {
  name = 'AI'

  constructor(
    interactor: Interactor,
    private agentService: AgentService
  ) {
    super(interactor)
  }

  protected hasChanged(context: CommandContext): boolean {
    return true
  }

  protected buildTools(context: CommandContext): CodayTool[] {
    const result: CodayTool[] = []

    const delegate = async ({ task, agentName }: { task: string; agentName: string | undefined }) => {
      if (context.stackDepth <= 0)
        return 'Delegation not allowed, either permanently, or existing capacity already used.'

      this.interactor.displayText(`DELEGATING to agent ${agentName} the task:\n${task}`)
      let agent: Agent | undefined
      if (agentName) {
        const matchingAgents = await this.agentService.findAgentByNameStart(agentName, context)
        if (matchingAgents.length === 0) {
          const output = `Agent ${agentName} not found.`
          this.interactor.displayText(output)
          return output
        }

        if (matchingAgents.length > 1) {
          const output = `Multiple agents found for: '${agentName}', possible matches: ${matchingAgents.map((a) => a.name).join(', ')}.`
          this.interactor.displayText(output)
          return output
        }

        agent = matchingAgents[0]
      } else {
        agent = await this.agentService.findByName('coday', context)
        if (!agent) {
          const output = `No default agent 'coday' found`
          this.interactor.displayText(output)
          return output + ', select one or avoid delegation.'
        }
      }
      console.log(`Agent identified: ${agent?.name}`)

      const forkedThread: AiThread = context.aiThread!.fork(agentName ? agent.name : undefined)
      const formattedTask: string = `You are given a task to try to complete. This task is part of a broader conversation given for context, but your current focus should be on this precise task:\n\n<task>${task}</task>`

      context.stackDepth--
      const delegatedEvents: Observable<CodayEvent> = (await agent.run(formattedTask, forkedThread)).pipe(
        tap((e) => {
          console.log(`delegated event ${e.type}`)
          let event: CodayEvent = e
          if (e instanceof MessageEvent) {
            event = new MessageEvent({ ...e, name: `-> ${e.name}` })
            // if (!e.name.startsWith("->")) {
            this.interactor.displayText(e.content, (event as MessageEvent).name)
            // }
          }
          this.interactor.sendEvent(event)
        })
      )
      const lastEvent = await lastValueFrom(delegatedEvents.pipe(filter((e) => e instanceof MessageEvent)))
      context.stackDepth++
      context.aiThread!.merge(forkedThread)
      return lastEvent.content
    }

    const agentSummaries = this.agentService
      .listAgentSummaries()
      .map((a) => `  - ${a.name} : ${a.description}`)
      .join('\n')
    const delegateTool: FunctionTool<{ task: string; agentName: string | undefined }> = {
      type: 'function',
      function: {
        name: 'delegate',
        description: `Delegate the completion of a task to another available agent among:
${agentSummaries}

These agents are LLM-based, so you should assess if the task was correctly executed, and call again the agent if not sufficient or need to adapt. Agents can be called again without loosing their context if more information is needed.
`,
        parameters: {
          type: 'object',
          properties: {
            agentName: {
              type: 'string',
              description:
                'Optional: name of the agent to target. Selects default agent if missing, fails if name is not matching. Recommended to select one fit for the task for relevant results.',
            },
            // withoutContext: {
            //   type: 'boolean',
            //   description: 'If present and true, delegates without the current conversation context. To use only for constrained agents that explicitly mention a limited context.'
            // },
            task: {
              type: 'string',
              description: `Description of the task to delegate, should contain:
                
  - intent
  - constraints
  - definition of done
  
  Take care to rephrase it as if you are the originator of the task.
                `,
            },
          },
        },
        parse: JSON.parse,
        function: delegate,
      },
    }
    result.push(delegateTool)

    if (!context.oneshot) {
      const queryUser = ({ message }: { message: string }) => {
        const command = `add-query ${message}`
        context.addCommands(command)
        return 'Query successfully queued, user will maybe answer later.'
      }

      const queryUserTool: FunctionTool<{ message: string }> = {
        type: 'function',
        function: {
          name: 'queryUser',
          description:
            'Queues asynchronously a query (question or request) for the user who may answer later, after this current run. IMPORTANT: Use this tool only when necessary, as it interrupts the flow of execution to seek user input.',
          parameters: {
            type: 'object',
            properties: {
              message: {
                type: 'string',
                description: 'The query to be added to the queue for user answer.',
              },
            },
          },
          parse: JSON.parse,
          function: queryUser,
        },
      }
      result.push(queryUserTool)
    }

    return result
  }
}

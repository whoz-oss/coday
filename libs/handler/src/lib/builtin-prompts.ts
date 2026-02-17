import { Prompt } from '@coday/model'

/**
 * Built-in system prompts
 *
 * These prompts are available out-of-the-box as slash commands.
 * They are read-only and not managed through PromptService CRUD operations.
 *
 * Built-in prompts are only visible when executing slash commands,
 * not in configuration/management interfaces.
 */
export const BuiltinPrompts: Prompt[] = [
  {
    id: 'builtin-re-prompt',
    name: 're-prompt',
    description: 'Transform a vague request into a detailed, actionable prompt',
    source: 'builtin',
    commands: [
      `@ I need you to analyze and reformulate the following request to make it more precise and actionable.

## User's initial request

{{PARAMETERS}}

## Your task

1. **Analyze the request** to identify:
   - What is explicitly stated
   - What is implicit or assumed
   - What information is missing or ambiguous
   - The likely intent behind the request

2. **Ask clarifying questions** if needed:
   - If the request is too vague or has multiple interpretations
   - If critical information is missing
   - Use the 'invite' or 'choice' tools to gather necessary details

3. **Reformulate the request** into a clear, detailed, and actionable prompt:
   - Make implicit requirements explicit
   - Add relevant context from the current project
   - Specify expected outcomes or deliverables
   - Break down complex requests into clear steps if needed
   - Use precise technical terminology when appropriate

4. **Present the reformulated request** and ask for confirmation:
   - Show the enhanced version clearly
   - Explain key changes or additions you made
   - Ask if this matches the user's intent

The goal is to transform a rough idea into a well-defined request that will produce better AI responses.`,
    ],
    createdBy: 'system',
    createdAt: new Date('2024-01-01').toISOString(),
    webhookEnabled: false,
    parameterFormat: '',
  },
]
